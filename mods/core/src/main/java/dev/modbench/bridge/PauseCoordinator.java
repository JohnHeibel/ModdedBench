// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 Modbench contributors
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
        default void warn(String message) { System.err.println("[Modbench] "+message); }
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
    private int fixtureTicks;

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
        out.addProperty("stepping",false);
        out.addProperty("clientConnected",host.clientConnected());
        out.addProperty("clientPaused",clientPaused);
        out.addProperty("deferredPackets",deferred.size());
        out.addProperty("scope","dedicated_server_all_dimensions");
        return out;
    }
    public Object command(Request r) {
        Consumer<JsonObject> reply=value->{if(value.has("error")) r.fail("clock_error",value.get("error").getAsString());else r.reply(value);};
        command(r.method,r.params,reply);
        if(completion==reply) owner=r;
        return null;
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
                    interrupt("superseded");clock.pause(reason);syncBoundary();
                    completion=reply;
                    operationDeadline=host.nanos()+Json.integer(params,"_timeout_ms",30000,1,3600000)*1_000_000L;
                    broadcast(true);return;
                }
                case "time.resume" -> {
                    if(clock.paused()) {
                        syncBoundary();
                        if(!settled()) throw new IllegalArgumentException("pause has not settled; inspect time.status before resuming");
                        computers.resume();background.resume();
                    }
                    interrupt("superseded");clock.resume();boundaryPaused=false;clientPaused=false;fixtureTicks=0;
                }
                default -> throw new IllegalArgumentException("unknown time method");
            }
            syncBoundary();broadcast(true);reply.accept(status());
        } catch(IllegalArgumentException error) { reply.accept(Json.object("error",error.getMessage())); }
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
    /** Development-only workload windows; no client lockstep or public stepping contract. */
    public void runForFixture(int ticks) {
        if(ticks<1 || ticks>2000 || !settled()) throw new IllegalArgumentException("fixture window requires settled pause and 1..2000 ticks");
        command("time.resume",new JsonObject(),result->{
            if(result.has("error")) throw new IllegalArgumentException(result.get("error").getAsString());
        });
        fixtureTicks=ticks;
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
    public void clientPaused(long ackGeneration) { if(clock.paused() && ackGeneration==generation) clientPaused=true; }
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
        deferred.removeIf(packet->!packet.open());
        syncBoundary();
        if(!clock.paused() && background.requested()) {
            background.resume();
            host.warn("background barrier was requested while the tick gate was open; resumed it to avoid a tick-lock deadlock");
        }
        if(owner!=null && (owner.isDone() || !owner.session.connected || owner.expired())) interrupt("pause_owner_lost");
        if(completion!=null) {
            if(computers.failure()!=null) interrupt("computer_pause_failed: "+computers.failure());
            else if(host.nanos()>operationDeadline) interrupt("pause_timeout; simulation remains gated");
            else if(settled()) {
                Consumer<JsonObject> done=completion;completion=null;owner=null;done.accept(status());
            }
        }
        broadcast(false);
        if(clock.paused()) return false;
        simulating=true;return true;
    }
    public void after() {
        simulating=false;clock.tickFinished();
        if(fixtureTicks>0 && --fixtureTicks==0) clock.pause("fixture_checkpoint");
    }
}
