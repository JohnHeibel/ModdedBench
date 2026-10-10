// SPDX-License-Identifier: MIT
// Copyright (c) 2026 ModdedBench contributors
package dev.modbench.bridge;

import com.google.gson.JsonObject;
import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.function.Consumer;

/**
 * Minecraft-free simulation-gate coordinator: pause ownership, client lockstep, background-work barriers and
 * the deferred gameplay FIFO. Everything runs on the simulation thread except {@link #simulating()} and
 * {@link #deferring()}, which the packet hook reads from Netty threads.
 * Invariant: a barrier is begun only while the tick gate is closed. GregTech's tick lock waits on every
 * admitted task from inside the gated tick, so a barrier requested while ticking would deadlock the server.
 */
public final class PauseCoordinator {
    /** Background-work barrier (GregTech {@link AsyncPause}, OpenComputers {@link ComputerPause}). */
    public interface Barrier {
        void begin();
        boolean requested();
        boolean ready();
        void resume();
        Object status();
        default String failure() { return null; }
    }
    /** Gameplay packet held while paused; replayed per connection in FIFO order on the first resumed tick. */
    public interface Deferred {
        boolean open();
        Object connection();
        void process();
    }
    /** Game-side services the coordinator drives. */
    public interface Host {
        boolean clientConnected();
        void send(JsonObject message);
        default void decorate(JsonObject status) {}
        default void warn(String message) { System.err.println("[ModdedBench] "+message); }
        default long nanos() { return System.nanoTime(); }
    }
    public final SimulationClock clock;
    private final Barrier background, computers;
    private final Host host;
    private final ArrayDeque<Deferred> deferred=new ArrayDeque<>();
    private long generation, lastBroadcast, operationDeadline;
    private boolean clientPaused, boundaryPaused;
    private volatile boolean simulating;
    private String lastMode="";
    private Consumer<JsonObject> completion;
    private Request owner;
    /** A step runs a counted number of ticks, then pauses with stepReason; the client gets the same allowance. */
    private int stepTicks, stepTotal;
    private long stepId;
    private String stepReason="step";
    private JsonObject lastStep;
    private boolean held;
    /** Whose hold it is, in the hold file's own word (operator, backup, compaction); null when the world is not held. */
    private String heldBy;
    /** Ticks a step still had when the operator's hold paused it: the release steps those, instead of running freely. */
    private int heldStep;

    public PauseCoordinator(SimulationClock clock, Barrier background, Barrier computers, Host host) {
        this.clock=clock;this.background=background;this.computers=computers;this.host=host;
    }
    public boolean simulating() { return simulating; }
    /** Paused and outside a simulating tick: gameplay packets must be deferred. */
    public boolean deferring() { return clock.paused() && !simulating; }
    public long generation() { return generation; }
    private boolean settled() { return boundaryPaused && background.ready() && computers.ready() && (!host.clientConnected() || clientPaused); }

    public JsonObject status() {
        JsonObject out=clock.status();
        if(clock.paused() && !settled()) out.addProperty("mode",computers.failure()==null?"pausing":"pause_error");
        host.decorate(out);
        out.addProperty("generation",generation);
        out.add("computers",Json.GSON.toJsonTree(computers.status()));
        out.add("gregtechUpdates",Json.GSON.toJsonTree(background.status()));
        boolean stepping=stepTotal>0 && !clock.paused();
        out.addProperty("stepping",stepping);
        if(stepping) out.add("step",Json.object("id",stepId,"ticks",stepTotal,"remaining",stepTicks));
        if(lastStep!=null) out.add("lastStep",lastStep);
        out.addProperty("held",held);
        if(held) out.addProperty("heldBy",heldBy);
        out.addProperty("clientConnected",host.clientConnected());
        out.addProperty("clientPaused",clientPaused);
        out.addProperty("deferredPackets",deferred.size());
        out.addProperty("scope","dedicated_server_all_dimensions");
        return out;
    }
    public Object command(Request r) {
        Consumer<JsonObject> reply=value->{if(value.has("error")) r.fail("clock_error",value.get("error").getAsString());else r.reply(value);};
        request(r.method,r.params,reply);
        if(completion==reply) owner=r;
        return null;
    }
    /**
     * A command from an agent: the bridge, or the client's clock channel. Only a hold may name a pause *_hold, because
     * that suffix is what a running job waits out and what a hold's release resumes.
     */
    public void request(String method, JsonObject params, Consumer<JsonObject> reply) {
        if(method.equals("time.pause") && SimulationClock.held(Json.string(params,"reason","")))
            reply.accept(Json.object("error","a pause reason may not end in _hold: that names a hold's pause"));
        else command(method,params,reply);
    }
    public void command(String method, JsonObject params, Consumer<JsonObject> reply) {
        try {
            switch(method) {
                case "time.status" -> { reply.accept(status());return; }
                case "time.configure" -> clock.configure(params);
                case "time.report_failure" -> { if(clock.actionFailed()) clock.pause("action_failed"); }
                case "time.pause" -> {
                    String reason=Json.string(params,"reason","requested_pause");
                    if(reason.isBlank()||reason.length()>256) throw new IllegalArgumentException("pause reason must contain 1..256 characters");
                    interrupt("superseded");clock.pause(reason);endStep(reason);syncBoundary();
                    completion=reply;
                    operationDeadline=host.nanos()+Json.integer(params,"_timeout_ms",30000,1,3600000)*1_000_000L;
                    broadcast(true);return;
                }
                case "time.resume", "time.step" -> {
                    int ticks=Json.integer(params,"ticks",0,0,72000);
                    if(method.equals("time.step") && ticks<1) throw new IllegalArgumentException("time.step needs ticks 1..72000");
                    if(held) throw new IllegalArgumentException(heldRefusal(heldBy));
                    if(clock.paused()) {
                        syncBoundary();
                        if(!settled()) throw new IllegalArgumentException("pause has not settled; inspect time.status before resuming");
                        computers.resume();background.resume();
                    }
                    interrupt("superseded");endStep("superseded");clock.resume();boundaryPaused=false;clientPaused=false;
                    if(ticks>0) { stepId++;stepTicks=stepTotal=ticks;stepReason="step"; }
                    if(method.equals("time.step")) { // replies once the step's own pause has settled
                        completion=reply;
                        operationDeadline=host.nanos()+Json.integer(params,"_timeout_ms",30000,1,3600000)*1_000_000L;
                        broadcast(true);return;
                    }
                }
                default -> throw new IllegalArgumentException("unknown time method");
            }
            syncBoundary();broadcast(true);reply.accept(status());
        } catch(IllegalArgumentException error) { reply.accept(Json.object("error",error.getMessage())); }
    }
    /**
     * A backup's or the compaction guard's hold ends by itself after this long, should its holder have died holding: a
     * backup copies the world in a minute or two and the guard lets go after ten minutes at the latest. The operator's never does.
     */
    public static final int STALE_HOLD_MINUTES=20;
    public static boolean expires(String by) { return "backup".equals(by) || "compaction".equals(by); }
    /** What a refused resume says: whose hold it is and what to expect of it. */
    public static String heldRefusal(String by) {
        String atMost=" (if it is not, the hold ends by itself within "+STALE_HOLD_MINUTES+" minutes)";
        if("backup".equals(by)) return "the world is held for a routine backup, which is over in under a minute"+atMost+": wait, then call again";
        if("compaction".equals(by)) return "the world was held while you were silent and is released within seconds"+atMost+": call again";
        return "the operator is holding the world paused; wait for the release";
    }
    public void hold(boolean value) { hold(value?"operator":null); }
    /** A hold, set from outside every agent-reachable path: the world pauses and resume is refused until release. by is whose it is, null for none. */
    public void hold(String by) {
        boolean value=by!=null,taken=value && held && !by.equals(heldBy);
        heldBy=by; // a hold taken over while held (the operator's Pause during a backup) changes hands here, and so does the pause it made
        if(taken && clock.paused() && SimulationClock.held(clock.reason())) { clock.pause(SimulationClock.holdReason(by));broadcast(true); }
        if(value==held) return;
        held=value;
        // A release resumes only the pause the hold made: a world already paused (an agent thinking, a guard,
        // a disconnect) or paused again during the hold stays paused, so a backup's hold never sets it running.
        if(!value) { if(clock.paused() && SimulationClock.held(clock.reason())) command("time.resume",Json.object("ticks",heldStep),result->{ if(result.has("error")) held=true; }); }
        else if(!clock.paused()) { heldStep=stepTicks;command("time.pause",Json.object("reason",SimulationClock.holdReason(by)),result->{}); }
    }
    private void interrupt(String reason) {
        if(completion!=null) { Consumer<JsonObject> previous=completion;completion=null;owner=null;
            previous.accept(Json.object("error",reason)); }
    }
    /** Barriers begin only here, after the clock is paused: the next before() closes the gate before any tick lock. */
    private void syncBoundary() {
        if(clock.paused() && !boundaryPaused) {
            boundaryPaused=true;clientPaused=false;generation++;background.begin();computers.begin();
        }
    }
    /** Development workload windows: a step that pauses with reason fixture_checkpoint. */
    public void runForFixture(int ticks) {
        if(ticks<1 || ticks>2000 || !settled()) throw new IllegalArgumentException("fixture window requires settled pause and 1..2000 ticks");
        command("time.resume",Json.object("ticks",ticks),result->{
            if(result.has("error")) throw new IllegalArgumentException(result.get("error").getAsString());
        });
        stepReason="fixture_checkpoint";
    }
    /** Closes the current step's record: how many ticks it ran and what ended it. */
    private void endStep(String why) {
        if(stepTotal==0) return;
        lastStep=Json.object("id",stepId,"ticks",stepTotal,"ran",stepTotal-stepTicks,"endedBy",why);
        stepTicks=stepTotal=0;
    }
    public void broadcast(boolean force) {
        syncBoundary();
        String mode=status().get("mode").getAsString();
        long now=host.nanos();
        if(force || !mode.equals(lastMode) || now-lastBroadcast>1_000_000_000L) {
            lastMode=mode;lastBroadcast=now;
            host.send(Json.object("type","state","state",status()));
        }
    }
    /** A (re)connecting client starts unpaused and receives the current state. */
    public void clientHello() { clientPaused=false;broadcast(true); }
    /** Generation-tagged ack: a stale ack cannot settle a newer pause. */
    public void clientPaused(long ackGeneration) { clientPaused(ackGeneration,-1,-1); }
    /** The client reports how many of its own ticks it ran in the step it last saw. */
    public void clientPaused(long ackGeneration, long step, int clientTicks) {
        if(clock.paused() && ackGeneration==generation) clientPaused=true;
        if(lastStep!=null && step==lastStep.get("id").getAsLong() && !lastStep.has("clientTicks")) lastStep.addProperty("clientTicks",clientTicks);
    }
    /** False when the paused queue is full: the clock pauses on overflow and the caller drops the connection. */
    public boolean defer(Deferred packet) {
        if(deferred.size()>=4096) { clock.pause("paused_packet_overflow");return false; }
        deferred.add(packet);return true;
    }
    /** Native network stage of a simulating tick: this connection's deferred FIFO precedes its new packets. */
    public void replay(Object connection) {
        if(!simulating) return;
        for(Iterator<Deferred> it=deferred.iterator();it.hasNext();) {
            Deferred next=it.next();
            if(!next.open()) { it.remove();continue; }
            if(next.connection()==connection) { it.remove();next.process(); }
        }
    }
    /** Tick gate: true admits one simulation tick. Call after game-side maintenance and message receipt. */
    public boolean before() {
        simulating=false;
        if(clock.paused()) endStep(clock.reason()); // a guard paused inside the step
        deferred.removeIf(packet->!packet.open());
        syncBoundary();
        if(!clock.paused() && background.requested()) {
            background.resume();
            host.warn("background barrier was requested while the tick gate was open; resumed it to avoid a tick-lock deadlock");
        }
        if(owner!=null && (owner.isDone() || !owner.session.connected)) interrupt("pause_owner_lost");
        if(completion!=null) {
            if(computers.failure()!=null) interrupt("computer_pause_failed: "+computers.failure());
            else if(host.nanos()>operationDeadline || owner!=null && owner.expired()) interrupt( // the owner's deadline is answered here, saying what became of the world
                stepTotal>0?"step_timeout; the world is still stepping":"pause_timeout; simulation remains gated");
            else if(settled()) {
                Consumer<JsonObject> done=completion;completion=null;owner=null;done.accept(status());
            }
        }
        broadcast(false);
        if(clock.paused()) return false;
        simulating=true;return true;
    }
    public void after() { after(()->{}); }
    /** observe looks at the host's guards before a step's own pause: one that fires on the step's last tick ends the step and is the reason. */
    public void after(Runnable observe) {
        simulating=false;clock.tickFinished();observe.run();
        if(stepTicks>0 && --stepTicks==0) { if(!clock.paused()) clock.pause(stepReason);endStep(clock.reason()); }
    }
}
