// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 ModdedBench contributors
package dev.modbench.bridge;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.Test;

/** Contract tests for the server-thread simulation-time policy. */
public class SimulationClockTest {
    private static final class Clock {
        final AtomicLong nanos = new AtomicLong();
        final SimulationClock policy = new SimulationClock(nanos::get);
        void advanceMs(long milliseconds) { nanos.addAndGet(milliseconds * 1_000_000L); }
    }

    @Test
    public void defaultsToRealtimeWithSafeDisconnectAndActionFailurePolicy() {
        SimulationClock clock = new SimulationClock(() -> 0L);

        assertFalse(clock.paused());
        assertTrue(clock.pauseOnDisconnect());
        assertFalse(clock.actionFailed());
        assertEquals("realtime", clock.status().get("mode").getAsString());
        assertEquals(0, clock.status().get("simulationTicks").getAsInt());
    }

    @Test
    public void pausedClockRejectsTicksAndResumeContinuesCounter() {
        SimulationClock clock = new SimulationClock(() -> 0L);
        clock.tickFinished();
        clock.pause("requested_pause");
        try { clock.tickFinished(); throw new AssertionError("paused tick accepted"); }
        catch(IllegalStateException expected) {}
        assertEquals(1,clock.status().get("simulationTicks").getAsInt());
        clock.resume();clock.tickFinished();
        assertEquals(2,clock.status().get("simulationTicks").getAsInt());
    }

    @Test
    public void healthDropUsesAnObservationBaselineAndThresholdsApplyDuringSimulation() {
        SimulationClock clock = new SimulationClock(() -> 0L);
        JsonObject config = new JsonObject();
        config.addProperty("healthDrop", true);
        config.addProperty("healthBelow", 5.0);
        clock.configure(config);

        clock.observe(20.0F, 300); // Establish the baseline; it must not itself pause.
        assertFalse(clock.paused());
        clock.observe(20.0F, 300);
        assertFalse(clock.paused());
        clock.observe(19.5F, 300);
        assertTrue(clock.paused());
        assertEquals("health_dropped", clock.status().get("reason").getAsString());

        clock.resume();
        clock.observe(5.0F, 300);
        assertTrue(clock.paused());
        assertEquals("health_threshold", clock.status().get("reason").getAsString());
    }

    @Test
    public void aNewThreatPausesOnceAndTheSameOneDoesNotPauseAgain() {
        SimulationClock clock = new SimulationClock(() -> 0L);
        clock.threats(threats("7")); // Off by default: seen, listed, not a reason to stop.
        assertFalse(clock.paused());
        JsonObject config = new JsonObject();
        config.addProperty("threatWithin", 12.0);
        clock.configure(config);

        clock.threats(threats("7"));
        assertTrue(clock.paused());
        assertEquals("threat", clock.status().get("reason").getAsString());
        assertEquals(1, clock.status().getAsJsonArray("threats").size());

        clock.resume();
        clock.threats(threats("7")); // The mob being fought.
        assertFalse(clock.paused());
        clock.threats(threats("7", "9")); // A second one joins.
        assertTrue(clock.paused());

        clock.resume();
        clock.threats(threats("9"));
        clock.threats(threats("9", "7")); // A mob that was hit drops its target for a moment: the same threat.
        assertFalse(clock.paused());
        clock.threats(threats("9"));
        for (int i = 0; i < 201; i++) clock.tickFinished();
        clock.threats(threats("9", "7")); // One that lost interest for ten seconds and came back is new again.
        assertTrue(clock.paused());

        clock.resume();
        clock.threats(threats("9", "7", "4!")); // A creeper that starts to swell changes its key.
        assertTrue(clock.paused());
    }

    private static JsonArray threats(String... keys) {
        JsonArray out = new JsonArray();
        for (String key : keys) out.add(Json.object("key", key));
        return out;
    }

    @Test
    public void airThresholdPausesAtItsInclusiveBoundary() {
        SimulationClock clock = new SimulationClock(() -> 0L);
        JsonObject config = new JsonObject();
        config.addProperty("airBelow", 42);
        clock.configure(config);

        clock.observe(20.0F, 43);
        assertFalse(clock.paused());
        clock.observe(20.0F, 42);
        assertTrue(clock.paused());
        assertEquals("air_threshold", clock.status().get("reason").getAsString());
    }

    @Test
    public void invalidConfigurationLeavesEveryConditionUntouched() {
        SimulationClock clock = new SimulationClock(() -> 0L);
        JsonObject valid = new JsonObject();
        valid.addProperty("healthDrop", true);
        valid.addProperty("healthBelow", 7.5);
        valid.addProperty("airBelow", 88);
        valid.addProperty("actionFailed", true);
        valid.addProperty("pauseOnDisconnect", false);
        clock.configure(valid);
        JsonObject before = Json.GSON.fromJson(
            Json.GSON.toJson(clock.status().getAsJsonObject("conditions")), JsonObject.class);

        JsonObject invalid = new JsonObject();
        invalid.addProperty("healthDrop", false);
        invalid.addProperty("airBelow", 301);
        try {
            clock.configure(invalid);
            throw new AssertionError("out-of-range configuration must fail");
        } catch (IllegalArgumentException expected) {
            // expected
        }

        assertEquals(before, clock.status().getAsJsonObject("conditions"));
        assertTrue(clock.actionFailed());
        assertFalse(clock.pauseOnDisconnect());
    }

    @Test
    public void eventHistoryIsBoundedAndPausedWallTimeIsSeparate() {
        Clock clock = new Clock();
        clock.advanceMs(10);
        clock.policy.pause("first");
        clock.advanceMs(25);
        assertEquals(35L, clock.policy.status().get("wallMs").getAsLong());
        assertEquals(25L, clock.policy.status().get("pausedMs").getAsLong());

        clock.policy.resume();
        clock.advanceMs(10_000);
        assertEquals(10_035L, clock.policy.status().get("wallMs").getAsLong());
        assertEquals(25L, clock.policy.status().get("pausedMs").getAsLong());
        assertEquals(0L, clock.policy.status().get("simulationTicks").getAsLong());

        for (int i = 0; i < 40; i++) clock.policy.pause("event-" + i);
        JsonArray events = clock.policy.status().getAsJsonArray("events");
        assertEquals(32, events.size());
        assertEquals("event-8", events.get(0).getAsJsonObject().get("reason").getAsString());
        assertEquals("event-39", events.get(31).getAsJsonObject().get("reason").getAsString());
    }

    @Test public void hungerAndFireGuardsAreOptInAndCanBeRearmedForRecovery() {
        SimulationClock clock = new SimulationClock(() -> 0L);
        clock.observe(20, 300, 0, true);
        assertFalse(clock.paused());
        clock.configure(Json.object("foodBelow", 6, "burning", true));
        clock.observe(20, 300, 7, false);
        assertFalse(clock.paused());
        clock.observe(20, 300, 6, false);
        assertEquals("food_threshold", clock.status().get("reason").getAsString());
        clock.configure(Json.object("foodBelow", -1));
        clock.resume();
        clock.observe(20, 300, 6, false);
        assertFalse(clock.paused());
        clock.observe(20, 300, 6, true);
        assertEquals("burning", clock.status().get("reason").getAsString());
        JsonObject before = clock.status().getAsJsonObject("conditions");
        try {
            clock.configure(Json.object("burning", false, "foodBelow", 21));
            fail("invalid threshold must not partially disable guards");
        } catch (IllegalArgumentException expected) { }
        assertEquals(before, clock.status().getAsJsonObject("conditions"));
    }

}
