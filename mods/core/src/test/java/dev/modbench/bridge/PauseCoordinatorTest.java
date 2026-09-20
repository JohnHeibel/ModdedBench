// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 Modbench contributors
package dev.modbench.bridge;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.Test;

/** Pause ownership, barrier ordering and deferred-packet replay, driven without Minecraft. */
public class PauseCoordinatorTest {
    /** Records every begin/resume together with whether the tick gate was closed at that moment. */
    static final class FakeBarrier implements PauseCoordinator.Barrier {
        final String name; final List<String> log; SimulationClock clock;
        boolean requested; int active; String failure;
        FakeBarrier(String name, List<String> log) { this.name=name; this.log=log; }
        @Override public void begin() { log.add(name+".begin gated="+clock.paused()); requested=true; }
        @Override public boolean requested() { return requested; }
        @Override public boolean ready() { return requested && active==0; }
        @Override public void resume() { log.add(name+".resume gated="+clock.paused()); requested=false; }
        @Override public String failure() { return failure; }
        @Override public Object status() { return Json.object("requested",requested,"active",active); }
    }
    static final class FakeHost implements PauseCoordinator.Host {
        boolean connected; final AtomicLong nanos=new AtomicLong(1_000_000_000L);
        final List<JsonObject> sent=new ArrayList<>(); final List<String> warnings=new ArrayList<>();
        @Override public boolean clientConnected() { return connected; }
        @Override public void send(JsonObject message) { sent.add(message); }
        @Override public void decorate(JsonObject status) { status.addProperty("worldId","fake"); }
        @Override public void warn(String message) { warnings.add(message); }
        @Override public long nanos() { return nanos.get(); }
    }
    record Packet(String name, Object connection, boolean open, List<String> processed) implements PauseCoordinator.Deferred {
        @Override public void process() { processed.add(name); }
    }
    static final class Fixture {
        final List<String> log=new ArrayList<>();
        final FakeBarrier gregtech=new FakeBarrier("gt",log), computers=new FakeBarrier("oc",log);
        final FakeHost host=new FakeHost();
        final SimulationClock clock=new SimulationClock(host.nanos::get);
        final PauseCoordinator coordinator;
        final List<JsonObject> replies=new ArrayList<>();
        Fixture() { gregtech.clock=computers.clock=clock; coordinator=new PauseCoordinator(clock,gregtech,computers,host); }
        void command(String method) { coordinator.command(method,new JsonObject(),replies::add); }
        String mode(JsonObject status) { return status.get("mode").getAsString(); }
    }

    @Test
    public void pausingGatesTheTickBeforeTheBarriersBegin() {
        Fixture f=new Fixture();
        assertTrue(f.coordinator.before());f.coordinator.after();
        f.command("time.pause");
        assertEquals(Arrays.asList("gt.begin gated=true","oc.begin gated=true"),f.log);
        assertTrue("pause reply waits for settle",f.replies.isEmpty());
        assertFalse("gate closed before any tick lock can run",f.coordinator.before());
        assertEquals(1,f.replies.size());assertEquals("paused",f.mode(f.replies.get(0)));
        assertEquals(1,f.coordinator.generation());
        assertEquals("state",f.host.sent.get(0).get("type").getAsString());
    }

    @Test
    public void settleWaitsForAdmittedWorkAndTheClientAckOfTheCurrentGeneration() {
        Fixture f=new Fixture();f.host.connected=true;f.gregtech.active=1;
        f.command("time.pause");
        assertFalse(f.coordinator.before());assertTrue(f.replies.isEmpty());
        assertEquals("pausing",f.mode(f.coordinator.status()));
        f.gregtech.active=0;
        f.coordinator.clientPaused(0); // stale generation
        assertFalse(f.coordinator.before());assertTrue(f.replies.isEmpty());
        f.command("time.resume");
        assertEquals("pause has not settled; inspect time.status before resuming",f.replies.get(0).get("error").getAsString());
        f.coordinator.clientPaused(f.coordinator.generation());
        assertFalse(f.coordinator.before());
        assertEquals(2,f.replies.size());assertEquals("paused",f.mode(f.replies.get(1)));
        assertTrue(f.replies.get(1).get("clientPaused").getAsBoolean());
    }

    @Test
    public void resumeReleasesBarriersBeforeTheClockAndReplaysDeferredFifoOnTheFirstSimulatedTick() {
        Fixture f=new Fixture();
        f.command("time.pause");assertFalse(f.coordinator.before());
        List<String> processed=new ArrayList<>();Object a=new Object(),b=new Object();
        assertTrue(f.coordinator.defer(new Packet("a1",a,true,processed)));
        assertTrue(f.coordinator.defer(new Packet("b1",b,true,processed)));
        assertTrue(f.coordinator.defer(new Packet("a2",a,true,processed)));
        assertTrue(f.coordinator.defer(new Packet("closed",a,false,processed)));
        f.coordinator.replay(a);
        assertTrue("nothing replays while paused",processed.isEmpty());
        assertEquals(4,f.coordinator.status().get("deferredPackets").getAsInt());
        f.log.clear();
        f.command("time.resume");
        assertEquals(Arrays.asList("oc.resume gated=true","gt.resume gated=true"),f.log);
        assertFalse(f.clock.paused());
        assertTrue(f.coordinator.before());
        assertTrue(f.coordinator.simulating());
        f.coordinator.replay(a);
        assertEquals(Arrays.asList("a1","a2"),processed);
        f.coordinator.replay(b);
        assertEquals(Arrays.asList("a1","a2","b1"),processed);
        assertEquals(0,f.coordinator.status().get("deferredPackets").getAsInt());
        f.coordinator.after();
        assertFalse(f.coordinator.simulating());
        assertEquals(1,f.clock.status().get("simulationTicks").getAsLong());
    }

    @Test
    public void barrierRequestedWhileTheGateIsOpenIsResumedWithAWarning() {
        Fixture f=new Fixture();
        f.gregtech.begin(); // a rogue caller; GregTech's tick lock would now block the next tick
        assertTrue(f.coordinator.before());
        assertFalse(f.gregtech.requested());
        assertEquals(1,f.host.warnings.size());
        assertTrue(f.host.warnings.get(0).contains("tick gate was open"));
        assertTrue(f.coordinator.before());
        assertEquals("the guard stays quiet once the invariant holds",1,f.host.warnings.size());
    }

    @Test
    public void deferredQueueOverflowPausesTheClockAndRejectsThePacket() {
        Fixture f=new Fixture();List<String> processed=new ArrayList<>();Object a=new Object();
        f.command("time.pause");
        for(int i=0;i<4096;i++) assertTrue(f.coordinator.defer(new Packet("p"+i,a,true,processed)));
        assertFalse(f.coordinator.defer(new Packet("overflow",a,true,processed)));
        assertEquals("paused_packet_overflow",f.clock.status().get("reason").getAsString());
    }

    @Test
    public void losingThePauseOwnerInterruptsTheCompletionAndKeepsTheGateClosed() {
        Fixture f=new Fixture();
        BridgeRuntime runtime=new BridgeRuntime("test") {
            @Override protected void controlsChanged(String reason) {}
            @Override protected void maintainControls() {}
        };
        Session session=new Session();List<JsonObject> replies=new ArrayList<>();
        f.host.connected=true; // never acks, so the pause cannot settle
        Request pause=new Request(new JsonPrimitive(1),"time.pause",new JsonObject(),session,runtime,replies::add);
        assertNull(f.coordinator.command(pause));
        assertFalse(f.coordinator.before());assertTrue(replies.isEmpty());
        session.disconnect();
        assertFalse(f.coordinator.before());
        assertEquals(1,replies.size());
        assertEquals("pause_owner_lost",replies.get(0).getAsJsonObject("error").get("msg").getAsString());
        assertTrue("simulation remains gated",f.clock.paused());
        assertFalse(f.coordinator.before());
    }

    @Test
    public void pauseTimeoutInterruptsButLeavesTheSimulationGated() {
        Fixture f=new Fixture();f.host.connected=true;
        f.command("time.pause");
        f.host.nanos.addAndGet(31_000_000_000L);
        assertFalse(f.coordinator.before());
        assertEquals("pause_timeout; simulation remains gated",f.replies.get(0).get("error").getAsString());
        assertTrue(f.clock.paused());
    }
}
