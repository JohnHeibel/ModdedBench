// SPDX-License-Identifier: MIT
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

    @Test
    public void turningTheThreatGuardOffForgetsTheThreatsItListed() {
        SimulationClock clock = new SimulationClock(() -> 0L);
        clock.configure(Json.object("threatWithin", 12.0));
        clock.threats(threats("7"));
        clock.resume();
        clock.configure(Json.object("healthDrop", true)); // Another guard's change leaves the list alone.
        assertEquals(1, clock.status().getAsJsonArray("threats").size());
        clock.configure(Json.object("threatWithin", -1)); // Nothing refreshes the list from here on: a stale mob must not stay "after you".
        assertEquals(0, clock.status().getAsJsonArray("threats").size());
    }

    @Test
    public void aFightQuietsItsOwnHitsAndMobsButNotTheOtherGuards() {
        SimulationClock clock = new SimulationClock(() -> 0L);
        JsonObject config = new JsonObject();
        config.addProperty("healthDrop", true);
        config.addProperty("healthBelow", 8.0);
        config.addProperty("threatWithin", 12.0);
        clock.configure(config);
        clock.fight(100, 8);

        clock.observe(20.0F, 300);
        clock.observe(15.0F, 300); // A hit taken in the fight.
        assertFalse(clock.paused());
        clock.threats(near("7", 2.0, "9", 7.5)); // The swarm it is fighting.
        assertFalse(clock.paused());
        clock.threats(near("7", 2.0, "11", 11.0)); // A skeleton beyond the fight is still news.
        assertTrue(clock.paused());
        assertEquals("threat", clock.status().get("reason").getAsString());

        clock.resume();
        clock.threats(near("7", 2.0, "4!", 3.0)); // So is a creeper starting to swell next to it.
        assertTrue(clock.paused());

        clock.resume();
        clock.observe(7.0F, 300); // And the health floor.
        assertTrue(clock.paused());
        assertEquals("health_threshold", clock.status().get("reason").getAsString());

        clock.resume();
        clock.fight(0, 0); // The fight ended: a hit pauses again.
        clock.observe(12.0F, 300);
        clock.observe(11.0F, 300);
        assertTrue(clock.paused());
        assertEquals("health_dropped", clock.status().get("reason").getAsString());
    }

    @Test
    public void aFightWindowRunsOutByItselfIfItsEndIsNeverReported() {
        SimulationClock clock = new SimulationClock(() -> 0L);
        JsonObject config = new JsonObject();
        config.addProperty("healthDrop", true);
        clock.configure(config);
        clock.fight(10, 4);
        for (int i = 0; i < 10; i++) clock.tickFinished();
        clock.observe(20.0F, 300);
        clock.observe(19.0F, 300);
        assertTrue(clock.paused());
    }

    private static JsonArray near(Object... keyDistance) {
        JsonArray out = new JsonArray();
        for (int i = 0; i < keyDistance.length; i += 2) out.add(Json.object("key", keyDistance[i], "distance", keyDistance[i + 1]));
        return out;
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
    public void aThresholdPausesOnceOnCrossingAndAgainOnlyAfterRecovering() {
        SimulationClock clock = new SimulationClock(() -> 0L);
        clock.configure(Json.object("airBelow", 180, "burning", true));
        clock.observe(20.0F, 180);
        assertEquals("air_threshold", clock.status().get("reason").getAsString());
        clock.resume();
        for (int air = 179; air > 100; air--) clock.observe(20.0F, air); // Swimming out: still low, not news.
        assertFalse(clock.paused());
        clock.observe(20.0F, 300); // Surfaced: the guard re-arms.
        clock.observe(20.0F, 170);
        assertTrue(clock.paused());

        clock.resume();
        clock.observe(20.0F, 300, 20, true);
        assertEquals("burning", clock.status().get("reason").getAsString());
        clock.resume();
        clock.observe(20.0F, 300, 20, true);
        assertFalse(clock.paused());
    }

    @Test
    public void aGuardIsReportedByItsOwnPauseAndNotSilencedByAnother() {
        SimulationClock clock = new SimulationClock(() -> 0L);
        clock.configure(Json.object("airBelow", 100, "burning", true, "healthDrop", true, "healthBelow", 8.0));
        clock.observe(20.0F, 300);
        clock.pause("step"); // A step ends, or the model pauses, with a value in danger that no pause has reported.
        clock.observe(20.0F, 90);
        clock.resume();
        clock.observe(20.0F, 90, 20, true);
        assertEquals("air_threshold", clock.status().get("reason").getAsString());
        clock.resume();
        clock.observe(20.0F, 80, 20, true); // Air is reported; the fire that came with it gets its own pause.
        assertEquals("burning", clock.status().get("reason").getAsString());
        clock.resume();
        clock.observe(20.0F, 70, 20, true);
        assertFalse(clock.paused());
        clock.observe(6.0F, 70, 20, true); // A hit that takes health under its floor is one event, reported once.
        assertEquals("health_dropped", clock.status().get("reason").getAsString());
        clock.resume();
        clock.observe(6.0F, 70, 20, true);
        assertFalse(clock.paused());
    }

    @Test
    public void aGuardsReasonIsKeptWhileItsPauseLasts() {
        SimulationClock clock = new SimulationClock(() -> 0L);
        clock.pause("health_dropped");
        clock.pause("client_disconnected");
        clock.pause("requested_pause");
        clock.pause("action_failed");
        assertEquals("health_dropped", clock.reason());
        assertEquals(1, clock.status().getAsJsonArray("events").size());
        clock.resume();
        clock.pause("requested_pause");
        clock.pause("action_failed"); // A pause that is only waited out still gives way to one that wants attention.
        assertEquals("action_failed", clock.reason());
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

        for (int i = 0; i < 40; i++) { clock.policy.resume(); clock.policy.pause("event-" + i); }
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

    @Test
    public void aRestartedClockComesBackWithItsGuardsAndThePauseIsSavedUnderItsOwnReason() {
        SimulationClock before = new SimulationClock(() -> 0L);
        before.configure(Json.object("healthDrop", true, "airBelow", 60, "threatWithin", 12, "pauseOnDisconnect", false));
        before.observe(20, 300); before.observe(15, 300);
        JsonObject saved = Json.GSON.fromJson(before.saved().toString(), JsonObject.class); // as read back from the file

        SimulationClock after = new SimulationClock(() -> 0L);
        after.restore(saved);
        assertEquals(before.status().getAsJsonObject("conditions"), after.status().getAsJsonObject("conditions"));
        assertFalse(after.pauseOnDisconnect());
        assertTrue(saved.get("paused").getAsBoolean());
        assertEquals("the guard that paused is still what the agent is told", "health_dropped", saved.get("reason").getAsString());
        assertFalse("the host applies the pause once the world may be gated", after.paused());
    }

    @Test
    public void aHoldsPauseIsNotSavedBecauseTheHoldFileRestoresIt() {
        SimulationClock clock = new SimulationClock(() -> 0L);
        assertFalse(clock.saved().get("paused").getAsBoolean());
        clock.pause("backup_hold");
        assertFalse("a release would find nothing of its own to resume", clock.saved().get("paused").getAsBoolean());
        assertTrue(clock.saved().get("reason").isJsonNull());
        clock.resume(); clock.pause("requested_pause");
        assertTrue(clock.saved().get("paused").getAsBoolean());
    }
}
