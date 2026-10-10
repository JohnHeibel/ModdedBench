// SPDX-License-Identifier: MIT
// Copyright (c) 2026 ModdedBench contributors
package dev.modbench.client;

import com.google.gson.JsonObject;
import dev.modbench.bridge.*;
import net.minecraft.client.Minecraft;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.play.client.C17PacketCustomPayload;
import net.minecraft.network.play.server.S3FPacketCustomPayload;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/** Render/control servicing continues while complete client simulation ticks are gated. */
public final class ClientClock implements ClockHooks.Driver {
    private final Minecraft mc=Minecraft.getMinecraft();
    private final ClientRuntime runtime;
    private final ConcurrentLinkedQueue<JsonObject> incoming=new ConcurrentLinkedQueue<>();
    private final LinkedHashMap<String,Request> pending=new LinkedHashMap<>();
    private final LinkedHashMap<String,ObservationFrames.Decoder> observationFrames=new LinkedHashMap<>();
    private NetworkManager connection;
    private JsonObject state=Json.object("mode","unavailable","paused",false);
    private boolean supported, paused, runningTick;
    private long lastHeartbeat, requestId, stepId;
    /** Ticks this client may still run in the server's current step (-1: no step), and how many it ran. */
    private int stepBudget=-1, stepRan;
    /**
     * Resume-and-act: an action that asked to resume a paused world runs the first tick here while the server is still
     * paused, then asks the server to resume. Its packets reach the server ahead of the resume on the same connection, so
     * the server defers them and replays them first in its first resumed tick: the action starts on that tick.
     */
    private boolean resuming, creditTick, resumeSent;
    private int resumeTicks, creditRan;
    /** Ticks added to the last step because it ended inside a single action, and whether the server refused one. */
    private int extended;
    private boolean extendRefused;
    private Session agent;
    /** The action whose _resume armed the credit tick, and the thread that admitted it. */
    private Request armed;
    private Thread armedThread;
    final PausedFrame presentation=new PausedFrame(this);
    private final SmoothView view=new SmoothView();
    private final PlanHold planHold=new PlanHold();

    public ClientClock(ClientRuntime runtime) { this.runtime=runtime; }
    public boolean isPaused() { return paused; }
    String pauseReason() {return Json.string(state,"reason","requested_pause");}
    public JsonObject status() {
        JsonObject out=Json.object("available",supported,"state",state,"clientPaused",paused,
            "clientSimulationTicks",runtime.tick(),"agentAttached",agent!=null && agent.connected,"presentation",presentation.status(),"planWhilePaused",planHold.status());
        return out;
    }
    public Object command(Request r) {
        if(r.method.equals("time.status")) return status();
        if(!supported || connection==null || !connection.isChannelOpen()) throw new IllegalArgumentException("server does not advertise ModdedBench time control");
        if(agent!=null && agent.connected && agent!=r.session) throw new IllegalArgumentException("time control belongs to another connected agent session");
        agent=r.session;
        String id=Long.toString(++requestId);pending.put(id,r);
        send(Json.object("type","command","id",id,"method",r.method,"params",r.params));
        return null;
    }
    public void actionFailed() { if(supported) send(Json.object("type","action_failed")); }
    public void expectThreat(int entityId) { if(supported) send(Json.object("type","expect_threat","entityId",entityId)); }
    /** A fight job started (ticks>0, its mobs within radius) or ended (0): see SimulationClock.fight. */
    public void fight(int ticks, double radius) { if(supported) send(Json.object("type","fight","ticks",ticks,"radius",radius)); }
    /**
     * Paused by anything that wants the model's attention: a guard, a failed action, or one of its own interrupt watches
     * (interrupt:NAME). Any reason not listed as waited out counts, so a new guard ends work without being added here.
     */
    boolean guardPause() { return paused && !SimulationClock.waitedOut(pauseReason()); }
    /** Paused where no tick will finish running work: by a guard, or at the end of a step. */
    boolean endsWork() { return !resuming && (guardPause() || paused && "step".equals(pauseReason())); }
    /** Paused for the purpose of refusing actions: false once an action has asked to resume. */
    boolean refusesActions() { return paused && !resuming; }
    /** Why a paused world refuses an action, with the pause's reason: a guard's pause is one to look at, not to resume through. */
    Refusal refusal(String what) { return refusal(pauseReason(),what); }
    static Refusal refusal(String reason,String what) { return new Refusal("time_paused",SimulationClock.waitedOut(reason)?"paused by "+reason+"; resume before "+what
        :"world paused by a guard ("+reason+"): read mb_time status, decide, resume"); }
    /**
     * Admits an action that carries _resume while the world is paused: true (resume) or N (step N ticks). The same
     * checks the server makes are made here first, so a refusal fails the call before anything runs.
     */
    void resumeFor(Request r,int ticks) {
        if(!paused || resuming) return;
        if(!supported || connection==null || !connection.isChannelOpen()) throw new IllegalArgumentException("server does not advertise ModdedBench time control");
        if(agent!=null && agent.connected && agent!=r.session) throw new IllegalArgumentException("time control belongs to another connected agent session");
        if(Json.bool(state,"held",false)) throw new IllegalArgumentException(PauseCoordinator.heldRefusal(Json.string(state,"heldBy","operator")));
        if(!"paused".equals(Json.string(state,"mode",""))) throw new IllegalArgumentException("pause has not settled; inspect time.status before resuming");
        agent=r.session;armed=r;armedThread=Thread.currentThread();resuming=creditTick=true;resumeSent=false;resumeTicks=ticks;extended=0;
        JsonObject record=Json.object("pausedBy",pauseReason(),"threats",state.has("threats")?state.get("threats"):new com.google.gson.JsonArray());
        if(ticks>0) record.addProperty("ticks",ticks);
        r.resumed=record;
    }
    /**
     * The action that asked for the resume ended in an error before the resume was sent: refused outright, or failed
     * on its own first tick (outcomes are collected at the end of that tick, ahead of the resume). It did nothing, so
     * the resume is withdrawn and the world stays paused; a step would otherwise run its ticks with the body idle.
     */
    void failed(Request r) {
        if(r!=armed || !resuming || resumeSent || Thread.currentThread()!=armedThread) return;
        resuming=creditTick=false;creditRan=0;armed=null;planHold.reset();
        r.resumed.addProperty("stayedPaused",true);
    }
    /** The session of the action that asked for the resume is gone (sendResume would send as nobody): the world stays paused. */
    static boolean dropped(Request armed) { return !armed.session.connected; }
    private void sendResume() {
        resumeSent=true;
        Request resume=new Request(new com.google.gson.JsonPrimitive("resume-for-action-"+requestId),"time.resume",
            Json.object("ticks",resumeTicks,"_timeout_ms",10000),agent,runtime,envelope->{
                if(envelope.get("ok").getAsBoolean()) return;
                resuming=false;creditRan=0;extendRefused=true;  // the world stays paused: the action that asked for it ends now, with why
                runtime.resumeRefused(envelope.getAsJsonObject("error").get("msg").getAsString());
            });
        String id=Long.toString(++requestId);pending.put(id,resume);
        send(Json.object("type","command","id",id,"method","time.resume","params",resume.params));
    }

    /**
     * A step never cuts a single action short (a click, a selection, a held input): when the step's pause finds one in
     * progress, the world steps one more tick, and again until the action answers. Only a step's own pause is extended:
     * a guard's, a hold's or a requested pause stands, and jobs are suspended at the step's end as before. The action's
     * own tick budget is what bounds it. True while the action is to be left running.
     */
    boolean extendStep() {
        String next=stepEnd(paused && !resuming && "step".equals(pauseReason()),"paused".equals(Json.string(state,"mode","")),
            !extendRefused && supported && agent!=null && agent.connected && armed!=null && !dropped(armed));
        if(next.equals("extend")) {
            // The tick runs here first and the server's follows it, as for the action's own first tick: a one-tick step
            // asked of the server alone is over before this client has run any of it.
            resuming=creditTick=true;resumeSent=false;creditRan=0;resumeTicks=1;extended++;
            if(armed.resumed!=null && !armed.isDone()) armed.resumed.addProperty("extendedTicks",extended); // the step ran longer than asked, and says so
        }
        return !next.equals("cancel");
    }
    /** What a busy single action does at a pause: cancel (not a step's pause, or nobody to step for), wait (the pause has not settled), extend. */
    static String stepEnd(boolean stepPause,boolean settled,boolean canStep) { return !stepPause || !canStep?"cancel":settled?"extend":"wait"; }
    String endedWhy() { return guardPause()?"world paused by a guard ("+pauseReason()+"): read mb_time status, decide, resume"
        :"the step ended and the world paused: step or resume to continue"; }
    void interruptPause(Request original,JsonObject receipt,String reason) {
        Request pause=new Request(new com.google.gson.JsonPrimitive("interrupt-pause-"+java.util.UUID.randomUUID()),"time.pause",
            Json.object("reason","interrupt:"+reason,"_timeout_ms",10000),original.session,runtime,envelope->{
                if(envelope.get("ok").getAsBoolean()) {receipt.addProperty("pauseConfirmed",true);receipt.add("pause",envelope.get("data"));}
                else receipt.add("pauseError",envelope.get("error"));
                original.reply(receipt);
            });
        command(pause);
    }
    public Object entity(Request r) {
        if(!supported||connection==null||!connection.isChannelOpen()) throw new IllegalArgumentException("ModdedBench server identity unavailable");
        JsonObject params=new JsonObject();
        if(r.params.has("uuid")) params.addProperty("uuid",java.util.UUID.fromString(r.params.get("uuid").getAsString()).toString());
        else {
            int id=Json.integer(r.params,"entityId",-1,0,Integer.MAX_VALUE);
            var e=mc.theWorld.getEntityByID(id);
            if(e==null||e.isDead||e.getDistanceSqToEntity(mc.thePlayer)>128*128) throw new IllegalArgumentException("entity is not loaded nearby");
            params.addProperty("entityId",id);
            params.addProperty("entityType",e instanceof net.minecraft.entity.player.EntityPlayer?"player":net.minecraft.entity.EntityList.getEntityString(e));
        }
        String id=Long.toString(++requestId);pending.put(id,r);
        send(Json.object("type","entity_observation","id",id,"params",params));return null;
    }
    void observe(Request original,JsonObject params,java.util.function.Consumer<JsonObject> finish) {
        if(!supported||connection==null||!connection.isChannelOpen())throw new IllegalArgumentException("authoritative observations require the ModdedBench server");
        if(pending.size()>=64)throw new IllegalArgumentException("too many pending server requests");
        if(Json.GSON.toJson(params).getBytes(StandardCharsets.UTF_8).length>24000)throw new IllegalArgumentException("observation request exceeds 24KiB; reduce batch");
        Object identity=runtime.identity();int dimension=mc.thePlayer.dimension;
        String id=Long.toString(++requestId);
        Request wrapped=new Request(new com.google.gson.JsonPrimitive("server-observation-"+id),original.method,original.params,original.session,runtime,envelope->{
            if(original.isDone())return;
            if(identity!=runtime.identity()||mc.thePlayer==null||mc.thePlayer.dimension!=dimension){original.fail("stale_context","world changed during server observation");return;}
            if(!envelope.get("ok").getAsBoolean()){JsonObject error=envelope.getAsJsonObject("error");original.fail(error.get("code").getAsString(),error.get("msg").getAsString());return;}
            try{finish.accept(envelope.getAsJsonObject("data"));}catch(Exception|LinkageError e){original.fail("observation_failed",e.toString());}
        });
        pending.put(id,wrapped);observationFrames.put(id,new ObservationFrames.Decoder());
        send(Json.object("type","observations","id",id,"params",params));
    }
    private void send(JsonObject data) {
        if(connection!=null && connection.isChannelOpen()) connection.scheduleOutboundPacket(new C17PacketCustomPayload("MB|Clock",
            Json.GSON.toJson(data).getBytes(StandardCharsets.UTF_8)));
    }
    @Override public void outgoing(Object packet) {runtime.ui.outgoing(packet);}
    @Override public void frameRendered() {view.rendered();presentation.rendered();}
    @Override public boolean packet(Object packet,Object handler) {
        runtime.ui.incoming(packet);
        if(packet instanceof net.minecraft.network.play.server.S19PacketEntityStatus status
            &&mc.theWorld!=null&&status.func_149160_c()==9&&status.func_149161_a(mc.theWorld)==mc.thePlayer)
            runtime.interactions.itemUseFinished();
        if(packet instanceof S3FPacketCustomPayload payload && "MB|Clock".equals(payload.func_149169_c())) {
            try { if(incoming.size()<1024) incoming.add(Json.GSON.fromJson(new String(payload.func_149168_d(),StandardCharsets.UTF_8),JsonObject.class)); }
            catch(RuntimeException error) { paused=true; }
            return true;
        }
        return false;
    }
    private void receive() {
        for(int i=0;i<1024;i++) {
            JsonObject data=incoming.poll();if(data==null) break;
            switch(Json.string(data,"type","")) {
                case "state" -> {
                    state=data.getAsJsonObject("state");supported=true;
                    if(state.has("worldId")) dev.modbench.api.ControlRegistry.memory().bind(state.get("worldId").getAsString());
                    paused=state.get("paused").getAsBoolean();
                    int credit=0;
                    if(!paused) { if(resuming) credit=creditRan;resuming=creditTick=false;creditRan=0;extendRefused=false;planHold.reset(); }
                    JsonObject step=state.has("step")?state.getAsJsonObject("step"):null;
                    if(step==null) stepBudget=-1;
                    else if(step.get("id").getAsLong()!=stepId) { // the credit tick was this step's first
                        stepId=step.get("id").getAsLong();stepBudget=Math.max(0,step.get("remaining").getAsInt()-credit);stepRan=credit; }
                    if(paused) send(Json.object("type","paused","generation",state.get("generation").getAsLong(),"step",stepId,"stepTicks",stepRan));
                }
                case "hurt" -> {
                    String type=Json.string(data,"damageType","unknown"),by=Json.string(data,"by","");float amount=data.get("amount").getAsFloat();
                    for(var listener:dev.modbench.api.GameEvents.listeners()) listener.hurt(type,by,amount);
                }
                case "reply" -> {
                    Request r=pending.remove(data.get("id").getAsString());
                    if(r!=null) { JsonObject result=data.getAsJsonObject("result");
                        if(result.has("error")) r.fail("clock_error",result.get("error").getAsString());else r.reply(result); }
                }
                case "observation_reply" -> {
                    String id=Json.string(data,"id","");Request r=pending.get(id);var decoder=observationFrames.get(id);
                    if(r==null||decoder==null)break;
                    try{JsonObject result=decoder.accept(data);if(result!=null){pending.remove(id);observationFrames.remove(id);if(result.has("error"))r.fail("observation_failed",result.get("error").getAsString());else r.reply(result);}}
                    catch(IllegalArgumentException error){pending.remove(id);observationFrames.remove(id);r.fail("observation_protocol_error",error.getMessage());}
                }
            }
        }
    }
    @Override public boolean before() {
        runningTick=false;
        NetworkManager current=mc.theWorld==null || mc.getNetHandler()==null?null:mc.getNetHandler().getNetworkManager();
        if(current!=connection) {
            for(Request r:pending.values()) r.fail("disconnected","clock connection changed");
            dev.modbench.api.ControlRegistry.memory().disconnected();
            pending.clear();observationFrames.clear();incoming.clear();connection=current;supported=false;paused=false;resuming=creditTick=false;creditRan=0;planHold.reset();
            state=Json.object("mode","unavailable","paused",false);
            if(connection!=null) send(Json.object("type","hello"));
        }
        // Vanilla notices a dead connection only inside a tick: a server that went away while paused must reopen the gate.
        if(paused && connection!=null && !connection.isChannelOpen()) paused=false;
        if((paused || stepBudget==0) && connection!=null) connection.processReceivedPackets(); // a spent step waits for the pause the same way
        receive();
        if(connection!=null && System.nanoTime()-lastHeartbeat>1_000_000_000L) {
            lastHeartbeat=System.nanoTime();send(Json.object("type","heartbeat"));
        }
        if(agent!=null && !agent.connected) { send(Json.object("type","agent_lost"));agent=null; }
        pending.entrySet().removeIf(entry->{
            Request r=entry.getValue();
            if(r.isDone() || r.expired() || !r.session.connected) {
                r.fail("timeout","clock request cancelled or expired");
                return true;
            }
            return false;
        });
        observationFrames.keySet().removeIf(id->!pending.containsKey(id));
        runtime.service(runtime.identity());
        if(paused || stepBudget==0) view.settle();
        if(creditTick && paused && dropped(armed)) { resuming=creditTick=false;planHold.reset(); }
        if(creditTick && paused) {
            // Plan-while-paused: the action's job plans with the world still paused, and its first tick follows the plan.
            if(planHold.hold(runtime::planningWhilePaused,runtime::whilePaused,System.nanoTime())) {
                if(mc.theWorld!=null) mc.entityRenderer.getMouseOver(1.0F);
                return false;
            }
            creditTick=false;creditRan=1;runningTick=true;runtime.simulationTick();return true;
        }
        if(paused || stepBudget==0) { // a spent step allowance waits for the server's pause like a pause does
            // GUI calls are serviced above; renderGameLoop remains running. No physical input polling here.
            planHold.gated(runtime::whilePaused);
            if(mc.theWorld!=null) mc.entityRenderer.getMouseOver(1.0F);
            return false;
        }
        if(stepBudget>0) { stepBudget--;stepRan++; }
        runningTick=true;
        runtime.simulationTick();
        return true;
    }
    @Override public boolean blockAction(int action,int x,int y,int z,int side) {return dev.modbench.api.ControlRegistry.memory().blockAction(action,x,y,z,side);}
    @Override public void after() {
        if(!runningTick) return;
        runtime.endTick();
        view.ticked();
        runningTick=false;
        if(resuming && !creditTick && !resumeSent) sendResume(); // after the tick's own packets, on the same connection
    }
}
