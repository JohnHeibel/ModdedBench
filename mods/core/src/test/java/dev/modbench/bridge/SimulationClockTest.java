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

    /** A clock with exactly these rules. */
    private static SimulationClock with(JsonObject... rules) {
        SimulationClock clock = new SimulationClock(() -> 0L);
        clock.rules(Json.object("replace", Json.array((Object[]) rules)));
        return clock;
    }

    /** A rule of this kind, named for it unless more says otherwise. */
    private static JsonObject rule(String kind, Object... more) {
        JsonObject out = Json.object("name", kind, "kind", kind);
        for (int i = 0; i < more.length; i += 2) out.add((String) more[i], Json.GSON.toJsonTree(more[i + 1]));
        return out;
    }

    private static JsonObject lastEvent(SimulationClock clock) {
        JsonArray events = clock.status().getAsJsonArray("events");
        return events.get(events.size() - 1).getAsJsonObject();
    }

    @Test
    public void defaultsToRealtimeWithSafeDisconnectAndTheDefaultRules() {
        SimulationClock clock = new SimulationClock(() -> 0L);

        assertFalse(clock.paused());
        assertTrue(clock.pauseOnDisconnect());
        assertEquals("realtime", clock.status().get("mode").getAsString());
        assertEquals(0, clock.status().get("simulationTicks").getAsInt());
        StringBuilder shown = new StringBuilder();
        for (var r : clock.status().getAsJsonArray("rules")) {
            JsonObject rule = r.getAsJsonObject();
            shown.append(rule.get("name").getAsString()).append(rule.has("level") ? " " + rule.get("level").getAsInt() : "").append(" ").append(rule.get("do").getAsString()).append("; ");
        }
        assertEquals("health_drop ask; health_below 8 ask; air_below 180 ask; food_below 6 ask; burning ask; hostile_within 12 ask; ", shown.toString());
        assertEquals(SimulationClock.defaults(), clock.rules(new JsonObject()).getAsJsonArray("rules"));
        assertEquals(12.0, clock.reach(), 0);
    }

    @Test
    public void eachDefaultRulePausesForItsOwnKind() {
        SimulationClock clock = new SimulationClock(() -> 0L);
        clock.observe(20.0F, 300, 20, false);
        clock.observe(19.0F, 300, 20, false);
        assertEquals("health_drop", clock.reason());
        clock.resume();
        clock.observe(8.0F, 300, 20, false);
        assertEquals("health_below", clock.reason());
        clock.resume();
        clock.observe(8.0F, 180, 20, false);
        assertEquals("air_below", clock.reason());
        clock.resume();
        clock.observe(8.0F, 180, 6, false);
        assertEquals("food_below", clock.reason());
        clock.resume();
        clock.observe(8.0F, 180, 6, true);
        assertEquals("burning", clock.reason());
        clock.resume();
        clock.threats(near("7", 12.0));
        assertEquals("hostile_within", clock.reason());
        clock.resume();
        clock.failed(); // action_failed is not a default
        assertFalse(clock.paused());
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
        SimulationClock clock = with(rule("health_drop"), rule("health_below", "level", 5.0));

        clock.observe(20.0F, 300); // Establish the baseline; it must not itself pause.
        assertFalse(clock.paused());
        clock.observe(20.0F, 300);
        assertFalse(clock.paused());
        clock.observe(19.5F, 300);
        assertTrue(clock.paused());
        assertEquals("health_drop", clock.status().get("reason").getAsString());
        assertEquals(19.5F, clock.status().getAsJsonObject("vitals").get("health").getAsFloat(), 0F); // the health the rule judged: the client has not been sent it

        clock.resume();
        clock.observe(5.0F, 300);
        assertTrue(clock.paused());
        assertEquals("health_below", clock.status().get("reason").getAsString());
    }

    @Test
    public void aNewThreatPausesOnceAndTheSameOneDoesNotPauseAgain() {
        SimulationClock clock = with();
        clock.threats(threats("7")); // With no rule: seen, listed, not a reason to stop.
        assertFalse(clock.paused());
        clock.rules(Json.object("set", Json.array(rule("hostile_within", "level", 12.0))));

        clock.threats(threats("7"));
        assertTrue(clock.paused());
        assertEquals("hostile_within", clock.status().get("reason").getAsString());
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
    public void removingTheLastHostileRuleForgetsTheThreatsItListed() {
        SimulationClock clock = with(rule("hostile_within", "level", 12.0));
        clock.threats(threats("7"));
        clock.resume();
        clock.rules(Json.object("set", Json.array(rule("health_drop")))); // Another rule's change leaves the list alone.
        assertEquals(1, clock.status().getAsJsonArray("threats").size());
        clock.rules(Json.object("remove", Json.array("hostile_within"))); // Nothing refreshes the list from here on: a stale mob must not stay "after you".
        assertEquals(0, clock.status().getAsJsonArray("threats").size());
        assertEquals(-1.0, clock.reach(), 0);
    }

    @Test
    public void aFightQuietsItsOwnHitsAndMobsButNotTheOtherRules() {
        SimulationClock clock = with(rule("health_drop", "name", "hit"), rule("health_below", "level", 8.0), rule("hostile_within", "level", 12.0));
        clock.fight(100, 8); // By kind: the health_drop rule here is called hit.

        clock.observe(20.0F, 300);
        clock.observe(15.0F, 300); // A hit taken in the fight.
        assertFalse(clock.paused());
        clock.threats(near("7", 2.0, "9", 7.5)); // The swarm it is fighting.
        assertFalse(clock.paused());
        clock.threats(near("7", 2.0, "11", 11.0)); // A skeleton beyond the fight is still news.
        assertTrue(clock.paused());
        assertEquals("hostile_within", clock.status().get("reason").getAsString());

        clock.resume();
        clock.threats(near("7", 2.0, "4!", 3.0)); // So is a creeper starting to swell next to it.
        assertTrue(clock.paused());

        clock.resume();
        clock.observe(7.0F, 300); // And the health floor.
        assertTrue(clock.paused());
        assertEquals("health_below", clock.status().get("reason").getAsString());

        clock.resume();
        clock.fight(0, 0); // The fight ended: a hit pauses again.
        clock.observe(12.0F, 300);
        clock.observe(11.0F, 300);
        assertTrue(clock.paused());
        assertEquals("health_drop:hit", clock.status().get("reason").getAsString());
    }

    @Test
    public void aFightOwnsBeingSetAlightUntilItEnds() {
        SimulationClock clock = with(rule("burning"), rule("health_below", "level", 8.0));
        clock.fight(100, 4);
        clock.observe(20.0F, 300, 20, true); // Set alight by what it is fighting.
        assertFalse(clock.paused());
        clock.observe(7.0F, 300, 20, true); // The health floor still holds.
        assertEquals("health_below", clock.status().get("reason").getAsString());

        clock.resume();
        clock.fight(0, 0); // The fight ended with the player still alight: that is news now.
        clock.observe(7.0F, 300, 20, true);
        assertEquals("burning", clock.status().get("reason").getAsString());
    }

    @Test
    public void aFightCarriesWhoIsAfterThePlayerAndSaysWhenThatChanged() {
        SimulationClock clock = new SimulationClock(() -> 0L);
        assertTrue(clock.status().get("fight").isJsonNull());
        clock.fight(100, 4);
        assertTrue(clock.engaged(new int[]{7})); // The first word is news, to be sent at once.
        assertFalse(clock.engaged(new int[]{7})); // The same again is not.
        assertEquals(7, clock.status().getAsJsonObject("fight").getAsJsonArray("engaged").get(0).getAsInt());
        clock.fight(100, 4); // A new fight starts knowing nothing.
        assertTrue(clock.engaged(new int[]{7}));
    }

    @Test
    public void aFightWindowRunsOutByItselfIfItsEndIsNeverReported() {
        SimulationClock clock = with(rule("health_drop"));
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
        SimulationClock clock = with(rule("air_below", "level", 42));

        clock.observe(20.0F, 43);
        assertFalse(clock.paused());
        clock.observe(20.0F, 42);
        assertTrue(clock.paused());
        assertEquals("air_below", clock.status().get("reason").getAsString());
    }

    @Test
    public void aThresholdPausesOnceOnCrossingAndAgainOnlyAfterRecovering() {
        SimulationClock clock = with(rule("air_below", "level", 180), rule("burning"));
        clock.observe(20.0F, 180);
        assertEquals("air_below", clock.status().get("reason").getAsString());
        clock.resume();
        for (int air = 179; air > 100; air--) clock.observe(20.0F, air); // Swimming out: still low, not news.
        assertFalse(clock.paused());
        clock.observe(20.0F, 300); // Surfaced: the rule re-arms.
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
    public void twoRulesCrossingOnOneTickMakeOneFreezeAndAPauseNoRuleMadeLetsThemWait() {
        SimulationClock clock = with(rule("air_below", "level", 100), rule("burning"), rule("health_drop"), rule("health_below", "level", 8.0));
        clock.observe(20.0F, 300);
        clock.pause("step"); // A step ends, or the model pauses, with a value in danger that no pause has reported.
        clock.observe(20.0F, 90);
        clock.resume();
        clock.observe(20.0F, 90, 20, true); // Air, and the fire that came on the same tick: one freeze, the first in the table names it.
        assertEquals("air_below", clock.status().get("reason").getAsString());
        assertEquals("[\"burning\"]", lastEvent(clock).get("with").toString());
        clock.observe(20.0F, 90, 20, true); // Looked at again under the same freeze: nothing joins twice.
        assertEquals(1, lastEvent(clock).getAsJsonArray("with").size());
        clock.resume();
        clock.observe(20.0F, 80, 20, true); // Both are reported.
        assertFalse(clock.paused());
        clock.observe(6.0F, 70, 20, true); // A hit that takes health under its floor: one freeze, the hit's, with the floor.
        assertEquals("health_drop", clock.status().get("reason").getAsString());
        assertEquals("[\"health_below\"]", lastEvent(clock).get("with").toString());
        clock.resume();
        clock.observe(6.0F, 70, 20, true);
        assertFalse(clock.paused());
    }

    @Test
    public void twoRulesOfOneKindAtDifferentLevelsEachCrossInTurn() {
        SimulationClock clock = with(rule("food_below", "name", "eat", "level", 16), rule("food_below", "name", "starving", "level", 6));
        clock.observe(20.0F, 300, 17, false);
        assertFalse(clock.paused());
        clock.observe(20.0F, 300, 16, false);
        assertEquals("food_below:eat", clock.reason()); // the kind and the rule's own name
        clock.resume();
        for (int food = 15; food > 6; food--) clock.observe(20.0F, 300, food, false);
        assertFalse(clock.paused());
        clock.observe(20.0F, 300, 6, false);
        assertEquals("food_below:starving", clock.reason());
        clock.resume();
        clock.observe(20.0F, 300, 20, false); // Eaten: both re-arm.
        clock.observe(20.0F, 300, 16, false);
        assertEquals("food_below:eat", clock.reason());

        clock = with(rule("hostile_within", "name", "fight", "level", 6), rule("hostile_within", "name", "close", "level", 3));
        assertEquals(6.0, clock.reach(), 0);
        clock.threats(near("7", 10.0));
        assertFalse(clock.paused());
        clock.threats(near("7", 5.0));
        assertEquals("hostile_within:fight", clock.reason());
        clock.resume();
        clock.threats(near("7", 4.0)); // Known to the one, out of the other's reach.
        assertFalse(clock.paused());
        clock.threats(near("7", 2.5)); // The same mob, now within the nearer level, is news to that rule.
        assertEquals("hostile_within:close", clock.reason());
        clock.resume();
        clock.threats(near("7", 2.0));
        assertFalse(clock.paused());
        JsonArray seen = new JsonArray();
        seen.add(Json.object("key", "8", "distance", 11.0, "lineOfSight", true)); // within twice the level in line of sight
        clock.threats(seen);
        assertEquals("hostile_within:fight", clock.reason());
    }

    @Test
    public void aTellRuleTellsOnceAndDoesNotFreeze() {
        SimulationClock clock = with(rule("food_below", "name", "peckish", "level", 16, "do", "tell"), rule("food_below", "level", 6));
        assertFalse(clock.news());
        clock.observe(20.0F, 300, 16, false);
        assertFalse(clock.paused());
        assertTrue(clock.news()); // the host sends the state at once
        assertFalse(clock.news());
        JsonArray told = clock.status().getAsJsonArray("told");
        assertEquals(1, told.size());
        assertEquals("food_below:peckish", told.get(0).getAsJsonObject().get("rule").getAsString());
        clock.observe(20.0F, 300, 15, false); // Told once as it crosses, like any level.
        assertEquals(1, clock.status().getAsJsonArray("told").size());
        clock.observe(20.0F, 300, 6, false); // The rule that asks still freezes.
        assertEquals("food_below", clock.reason());
        assertEquals("tell", clock.status().getAsJsonArray("rules").get(0).getAsJsonObject().get("do").getAsString());
        assertEquals("ask", clock.status().getAsJsonArray("rules").get(1).getAsJsonObject().get("do").getAsString());
    }

    @Test
    public void anActionFailureCrossesItsRulesEvenAtAStepsEnd() {
        SimulationClock clock = with();
        clock.failed();
        assertFalse(clock.paused());
        clock.rules(Json.object("set", Json.array(rule("action_failed"))));
        clock.pause("step"); // The job failed on the step's last tick; the client's word comes after the step's pause.
        clock.failed();
        assertEquals("action_failed", clock.reason());
    }

    @Test
    public void aRuleGivenAgainUnchangedKeepsWhatItReportedAndAChangedOneStartsAfresh() {
        SimulationClock clock = with(rule("air_below", "level", 100), rule("burning"));
        clock.observe(20.0F, 90);
        assertEquals("air_below", clock.reason());
        clock.resume();
        clock.rules(Json.object("set", Json.array(rule("air_below", "level", 100)))); // a task's change put back
        clock.observe(20.0F, 85);
        assertFalse(clock.paused());
        clock.rules(Json.object("set", Json.array(rule("air_below", "level", 120))));
        clock.observe(20.0F, 85);
        assertEquals("air_below", clock.reason());
        assertEquals("air_below", clock.status().getAsJsonArray("rules").get(0).getAsJsonObject().get("name").getAsString()); // in its place
    }

    @Test
    public void aBurnPausesHealthDropOnce() {
        SimulationClock clock = with(rule("health_drop"));
        clock.observe(20.0F, 300, 20, true);
        clock.hurt("onFire",false);
        clock.observe(19.0F, 300, 20, true); // The burn's first tick is news.
        assertEquals("health_drop", clock.status().get("reason").getAsString());
        clock.resume();
        clock.observe(19.0F, 300, 20, true);
        clock.hurt("onFire",false);
        clock.observe(18.0F, 300, 20, true);
        clock.hurt("onFire",false);
        clock.observe(17.0F, 300, 20, true); // Its later ticks are not.
        assertFalse(clock.paused());
        clock.hurt("lava",false);
        clock.observe(13.0F, 300, 20, true); // Lava while still alight is: dragon run 3 fell into it unpaused.
        assertEquals("health_drop", clock.status().get("reason").getAsString());
        clock.resume();
        clock.observe(13.0F, 300, 20, true);
        clock.hurt("onFire",false);clock.hurt("mob",true);
        clock.observe(10.0F, 300, 20, true); // So is a hit in the same tick as a burn tick.
        assertEquals("health_drop", clock.status().get("reason").getAsString());
        clock.resume();
        clock.observe(17.0F, 300, 20, false);
        clock.observe(16.0F, 300, 20, false); // A loss that no lasting hurt explains is news.
        assertEquals("health_drop", clock.status().get("reason").getAsString());
        clock.resume();
        clock.rules(Json.object("set", Json.array(rule("burning"))));
        clock.observe(16.0F, 300, 20, true); // Set alight: the burning rule reports the burn...
        assertEquals("burning", clock.status().get("reason").getAsString());
        clock.resume();
        clock.observe(16.0F, 300, 20, true);
        clock.hurt("onFire",false);
        clock.observe(15.0F, 300, 20, true); // ...so its first tick does not pause again.
        assertFalse(clock.paused());
    }

    @Test
    public void lastingHarmOfAnyKindPausesOnceUntilItStops() {
        SimulationClock clock = with(rule("health_drop"));
        clock.observe(20.0F, 300, 20, false);
        clock.hurt("magic",false); clock.tickFinished();
        clock.observe(19.0F, 300, 20, false); // A poison's first tick is news.
        assertEquals("health_drop", clock.status().get("reason").getAsString());
        clock.resume();
        clock.observe(19.0F, 300, 20, false);
        for(int t=0;t<25;t++) clock.tickFinished();
        clock.hurt("magic",false);
        clock.observe(18.0F, 300, 20, false); // Its later ticks are not.
        assertFalse(clock.paused());
        clock.hurt("someMod.heat",false);
        clock.observe(17.0F, 300, 20, false); // Another kind, whatever its name, is.
        assertEquals("health_drop", clock.status().get("reason").getAsString());
        clock.resume();
        clock.observe(17.0F, 300, 20, false);
        clock.hurt("magic",true);
        clock.observe(16.0F, 300, 20, false); // A hit by something always is, even of a lasting kind.
        assertEquals("health_drop", clock.status().get("reason").getAsString());
        clock.resume();
        clock.observe(16.0F, 300, 20, false);
        for(int t=0;t<101;t++) clock.tickFinished();
        clock.hurt("magic",false);
        clock.observe(15.0F, 300, 20, false); // Stopped for over 5 seconds: news again.
        assertEquals("health_drop", clock.status().get("reason").getAsString());
        clock.resume();
        clock.rules(Json.object("set", Json.array(rule("burning"))));
        clock.observe(15.0F, 300, 20, false);
        clock.hurt("magic",false);
        clock.observe(14.0F, 300, 20, false); // Changing the rules does not make it news again.
        assertFalse(clock.paused());
    }

    @Test
    public void aRulesReasonIsKeptWhileItsPauseLasts() {
        SimulationClock clock = new SimulationClock(() -> 0L);
        clock.pause("health_drop");
        clock.pause("client_disconnected");
        clock.pause("requested_pause");
        clock.pause("action_failed");
        assertEquals("health_drop", clock.reason());
        assertEquals(1, clock.status().getAsJsonArray("events").size());
        clock.resume();
        clock.pause("requested_pause");
        clock.pause("action_failed"); // A pause that is only waited out still gives way to one that wants attention.
        assertEquals("action_failed", clock.reason());
    }

    @Test
    public void anInvalidChangeLeavesTheWholeTableUntouched() {
        SimulationClock clock = new SimulationClock(() -> 0L);
        JsonObject before = clock.rules(new JsonObject());
        for (JsonObject bad : new JsonObject[]{
                Json.object("set", Json.array(rule("air_below", "level", 301))),
                Json.object("set", Json.array(rule("health_below"))), // no level
                Json.object("set", Json.array(rule("burning", "level", 3))), // a level it does not take
                Json.object("set", Json.array(rule("lava_lake"))), // no such kind
                Json.object("set", Json.array(rule("check"))), // a check is the model's own, kept by the Python side
                Json.object("set", Json.array(Json.object("name", "Bad Name", "kind", "burning"))),
                Json.object("remove", Json.array("burning"), "set", Json.array(rule("food_below", "level", 21)))}) {
            try {
                clock.rules(bad);
                fail("accepted " + bad);
            } catch (IllegalArgumentException expected) { }
        }
        assertEquals(before, clock.rules(new JsonObject()));
        try {
            clock.configure(Json.object("healthDrop", false));
            fail("configure is not where danger is set");
        } catch (IllegalArgumentException expected) { }
        clock.configure(Json.object("pauseOnDisconnect", false));
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

    @Test public void aRuleRemovedStopsAndARuleAddedStarts() {
        SimulationClock clock = with();
        clock.observe(20, 300, 0, true);
        assertFalse(clock.paused());
        clock.rules(Json.object("set", Json.array(rule("food_below", "level", 6), rule("burning"))));
        clock.observe(20, 300, 7, false);
        assertFalse(clock.paused());
        clock.observe(20, 300, 6, false);
        assertEquals("food_below", clock.status().get("reason").getAsString());
        clock.rules(Json.object("remove", Json.array("food_below")));
        clock.resume();
        clock.observe(20, 300, 6, false);
        assertFalse(clock.paused());
        clock.observe(20, 300, 6, true);
        assertEquals("burning", clock.status().get("reason").getAsString());
    }

    @Test
    public void aRestartedClockComesBackWithItsRulesAndThePauseIsSavedUnderItsOwnReason() {
        SimulationClock before = with(rule("health_drop"), rule("air_below", "level", 60), rule("hostile_within", "level", 12),
            rule("burning", "name", "alight", "do","def main(event):\n    pass\n"));
        before.configure(Json.object("pauseOnDisconnect", false));
        before.observe(20, 300); before.observe(15, 300);
        JsonObject saved = Json.GSON.fromJson(before.saved().toString(), JsonObject.class); // as read back from the file

        SimulationClock after = new SimulationClock(() -> 0L);
        after.restore(saved);
        assertEquals(before.rules(new JsonObject()), after.rules(new JsonObject()));
        assertEquals("script", after.status().getAsJsonArray("rules").get(3).getAsJsonObject().get("do").getAsString());
        assertFalse(after.pauseOnDisconnect());
        assertTrue(saved.get("paused").getAsBoolean());
        assertEquals("the rule that paused is still what the agent is told", "health_drop", saved.get("reason").getAsString());
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
