// SPDX-License-Identifier: MIT
// Copyright (c) 2026 ModdedBench contributors
package dev.modbench.bridge;

import com.google.gson.JsonObject;
import java.util.ArrayDeque;
import java.util.function.LongSupplier;

/** Server-thread policy. Wall time is recorded separately from simulation time. */
public final class SimulationClock {
    private final LongSupplier nanos;
    private final long started;
    private long changed, pausedNanos, simulationTicks, eventSequence;
    private volatile boolean paused;
    private boolean healthDrop, actionFailed, burning, pauseOnDisconnect = true;
    private int airBelow = -1, foodBelow = -1;
    private double healthBelow = -1, threatWithin = -1;
    private long fightUntil = -1; private double fightRadius; // a fight job the model started: its hits and its mobs are not news
    private final java.util.Map<String,Long> knownThreats = new java.util.HashMap<>(); // key -> tick last seen
    private com.google.gson.JsonArray threats = new com.google.gson.JsonArray();
    private Float lastHealth;
    private final boolean[] inDanger = new boolean[4]; // health, air, burning, food: already reported, not yet recovered
    private String reason = "startup";
    private final ArrayDeque<JsonObject> events = new ArrayDeque<>();

    public SimulationClock() { this(System::nanoTime); }
    public SimulationClock(LongSupplier nanos) { this.nanos=nanos; started=changed=nanos.getAsLong(); }
    public boolean paused() { return paused; }
    public long ticks() { return simulationTicks; }
    public String reason() { return reason; }
    public boolean pauseOnDisconnect() { return pauseOnDisconnect; }
    public boolean actionFailed() { return actionFailed; }
    /** Pauses a running job waits out: a request, a hold, a lost connection or a clock fault. Any other reason is a guard's. */
    private static final java.util.Set<String> WAITED_OUT=java.util.Set.of("requested_pause","client_disconnected",
        "client_unresponsive","agent_disconnected","paused_packet_overflow","clock_protocol_error","step");
    public static boolean waitedOut(String reason) { return WAITED_OUT.contains(reason) || held(reason); }
    /** A hold's pause is named for its holder (operator_hold, backup_hold), so a routine backup never reads as a person stepping in. */
    public static String holdReason(String by) { return by+"_hold"; }
    public static boolean held(String reason) { return reason.endsWith("_hold"); }
    public void pause(String why) {
        if(paused && !waitedOut(reason)) return; // a guard's reason is what the model is told: nothing overwrites it until the resume
        boolean newEvent = !paused || !reason.equals(why);
        transition(true); reason=why;
        if (newEvent) {
            events.add(Json.object("sequence", ++eventSequence, "tick", simulationTicks, "reason", why,
                "wallMs", (nanos.getAsLong()-started)/1_000_000L));
            while(events.size()>32) events.removeFirst();
        }
    }
    public void resume() { transition(false); reason="requested_resume"; lastHealth=null; }
    public void tickFinished() {
        if(paused) throw new IllegalStateException("no simulation tick permitted while paused");
        simulationTicks++;
    }
    public void observe(float health, int air) {
        observe(health, air, 20, false);
    }
    /**
     * Thresholds and burning pause once, as the value crosses into danger, and re-arm only when it has recovered:
     * a guard that re-paused every tick while air stayed low would leave no ticks to swim out with. Only the guard
     * that paused counts as reported: a value in danger under another pause (a step's end, another guard) pauses in its turn.
     */
    public void observe(float health, int air, int food, boolean onFire) {
        boolean lowHealth=healthBelow>=0 && health<=healthBelow, lowAir=airBelow>=0 && air<=airBelow;
        boolean lowFood=foodBelow>=0 && food<=foodBelow, fire=burning && onFire;
        if(!paused) {
            if(healthDrop && !fighting() && lastHealth!=null && health<lastHealth) { pause("health_dropped"); inDanger[0]=lowHealth; } // the hit reports the health it left
            else if(lowHealth && !inDanger[0]) { pause("health_threshold"); inDanger[0]=true; }
            else if(lowAir && !inDanger[1]) { pause("air_threshold"); inDanger[1]=true; }
            else if(fire && !inDanger[2]) { pause("burning"); inDanger[2]=true; }
            else if(lowFood && !inDanger[3]) { pause("food_threshold"); inDanger[3]=true; }
        }
        inDanger[0]&=lowHealth; inDanger[1]&=lowAir; inDanger[2]&=fire; inDanger[3]&=lowFood;
        lastHealth=health;
    }
    public double threatWithin() { return threatWithin; }
    /**
     * Mobs that are after the player right now, as the server knows them: [{key,...what a player would perceive}].
     * A key that was not there on the last look pauses ("threat"); one the agent already resumed past does not,
     * so a fight is not re-paused every tick by the mob being fought.
     */
    /** The mob a fight job was sent after is expected to notice the player: not news for as long as a fight can last. */
    public void expectThreat(int entityId) { knownThreats.put(String.valueOf(entityId), simulationTicks+6000); knownThreats.put(entityId+"!", simulationTicks+6000); }
    /**
     * A fight job runs for at most `ticks`: until it ends, taking a hit does not pause (healthDrop), and neither does a mob
     * taking the player as its target within `radius` blocks, which is the fight the model chose. Every other guard stays
     * armed: health, air, burning and food thresholds, a creeper starting to swell, and threats beyond the radius. 0 ends it.
     */
    public void fight(int ticks, double radius) { fightUntil = ticks > 0 ? simulationTicks + ticks : -1; fightRadius = radius; lastHealth = null; }
    public boolean fighting() { return simulationTicks < fightUntil; }
    public void threats(com.google.gson.JsonArray now) {
        boolean fresh = false; knownThreats.values().removeIf(seen -> simulationTicks - seen > 200);
        // A mob that is hit drops its target for a few ticks: it is the same threat when it comes back, not a new one.
        for (com.google.gson.JsonElement t : now) {
            JsonObject threat = t.getAsJsonObject(); String key = threat.get("key").getAsString();
            boolean joined = fighting() && !key.endsWith("!") && threat.has("distance") && threat.get("distance").getAsDouble() <= fightRadius;
            fresh |= knownThreats.put(key, simulationTicks) == null && !joined;
        }
        threats = now;
        if (fresh && !paused && threatWithin >= 0) pause("threat");
    }
    public void configure(JsonObject p) {
        boolean hd=Json.bool(p,"healthDrop",healthDrop), af=Json.bool(p,"actionFailed",actionFailed);
        boolean disconnect=Json.bool(p,"pauseOnDisconnect",pauseOnDisconnect);
        double hb=Json.number(p,"healthBelow",healthBelow,-1,100000);
        int ab=Json.integer(p,"airBelow",airBelow,-1,300);
        int fb=Json.integer(p,"foodBelow",foodBelow,-1,20);
        boolean fire=Json.bool(p,"burning",burning);
        double tw=Json.number(p,"threatWithin",threatWithin,-1,32);
        healthDrop=hd; actionFailed=af; pauseOnDisconnect=disconnect; healthBelow=hb; airBelow=ab;
        foodBelow=fb; burning=fire; threatWithin=tw; knownThreats.clear();
        lastHealth=null; java.util.Arrays.fill(inDanger,false);
        if(threatWithin<0) threats=new com.google.gson.JsonArray(); // the host stops looking for threats: none it listed before is still known to be one
    }
    /**
     * What a restarted server needs to come back as it was: the guards, and the pause with its reason. A hold's pause is
     * left out: the hold file is what restores it, and a release must find nothing of its own still paused.
     */
    public JsonObject saved() {
        boolean kept=paused && !held(reason);
        return Json.object("paused",kept,"reason",kept?reason:null,"conditions",conditions());
    }
    /** The guards of {@link #saved()}; the host pauses again, with the saved reason, once the world may be gated. */
    public void restore(JsonObject saved) { if(saved.has("conditions")) configure(saved.getAsJsonObject("conditions")); }
    private JsonObject conditions() {
        return Json.object("healthDrop",healthDrop,"healthBelow",healthBelow,"airBelow",airBelow,
            "foodBelow",foodBelow,"burning",burning,"threatWithin",threatWithin,
            "actionFailed",actionFailed,"pauseOnDisconnect",pauseOnDisconnect);
    }
    private void transition(boolean value) {
        if(paused==value) return;
        long now=nanos.getAsLong();
        if(paused) pausedNanos+=now-changed;
        paused=value; changed=now;
    }
    public JsonObject status() {
        long now=nanos.getAsLong();
        return Json.object("mode", paused?"paused":"realtime", "paused",paused,
            "simulationTicks",simulationTicks,"reason",reason,
            "wallMs",(now-started)/1_000_000L,"pausedMs",(pausedNanos+(paused?now-changed:0))/1_000_000L,
            "conditions",conditions(),"threats",threats,"events",events,
            "fight",fighting()?Json.object("untilTick",fightUntil,"radius",fightRadius):null);
    }
}
