// SPDX-License-Identifier: LGPL-3.0-or-later
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
    private long lastHeartbeat, requestId;
    private Session agent;
    final PausedFrame presentation=new PausedFrame(this);

    public ClientClock(ClientRuntime runtime) { this.runtime=runtime; }
    public boolean isPaused() { return paused; }
    String pauseReason() {return Json.string(state,"reason","requested_pause");}
    public JsonObject status() {
        JsonObject out=Json.object("available",supported,"state",state,"clientPaused",paused,
            "clientSimulationTicks",runtime.tick(),"agentAttached",agent!=null && agent.connected,"presentation",presentation.status());
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
    /** Paused by a guard, which wants the model's attention, rather than by a request or a lost connection, which a job waits out. */
    boolean guardPause() { return paused && java.util.Set.of("threat","health_dropped","health_threshold","air_threshold","food_threshold","burning").contains(pauseReason()); }
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
    @Override public void frameRendered() {presentation.rendered();}
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
                    if(paused) send(Json.object("type","paused","generation",state.get("generation").getAsLong()));
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
            pending.clear();observationFrames.clear();incoming.clear();connection=current;supported=false;paused=false;
            state=Json.object("mode","unavailable","paused",false);
            if(connection!=null) send(Json.object("type","hello"));
        }
        // Vanilla notices a dead connection only inside a tick: a server that went away while paused must reopen the gate.
        if(paused && connection!=null && !connection.isChannelOpen()) paused=false;
        if(paused && connection!=null) connection.processReceivedPackets();
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
        if(paused) {
            // GUI calls are serviced above; renderGameLoop remains running. No physical input polling here.
            if(mc.theWorld!=null) mc.entityRenderer.getMouseOver(1.0F);
            return false;
        }
        runningTick=true;
        runtime.simulationTick();
        return true;
    }
    @Override public boolean blockAction(int action,int x,int y,int z,int side) {return dev.modbench.api.ControlRegistry.memory().blockAction(action,x,y,z,side);}
    @Override public void after() {
        if(!runningTick) return;
        runtime.endTick();
        dev.modbench.api.ControlRegistry.memory().endTick();
        runningTick=false;
    }
}
