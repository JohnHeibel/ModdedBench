// SPDX-License-Identifier: LGPL-3.0-or-later
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
    private java.util.Set<String> knownThreats = new java.util.HashSet<>();
    private com.google.gson.JsonArray threats = new com.google.gson.JsonArray();
    private Float lastHealth;
    private String reason = "startup";
    private final ArrayDeque<JsonObject> events = new ArrayDeque<>();

    public SimulationClock() { this(System::nanoTime); }
    public SimulationClock(LongSupplier nanos) { this.nanos=nanos; started=changed=nanos.getAsLong(); }
    public boolean paused() { return paused; }
    public long ticks() { return simulationTicks; }
    public boolean pauseOnDisconnect() { return pauseOnDisconnect; }
    public boolean actionFailed() { return actionFailed; }
    public void pause(String why) {
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
    public void observe(float health, int air, int food, boolean onFire) {
        if(!paused) {
            if(healthDrop && lastHealth!=null && health<lastHealth) pause("health_dropped");
            else if(healthBelow>=0 && health<=healthBelow) pause("health_threshold");
            else if(airBelow>=0 && air<=airBelow) pause("air_threshold");
            else if(burning && onFire) pause("burning");
            else if(foodBelow>=0 && food<=foodBelow) pause("food_threshold");
        }
        lastHealth=health;
    }
    public double threatWithin() { return threatWithin; }
    /**
     * Mobs that are after the player right now, as the server knows them: [{key,...what a player would perceive}].
     * A key that was not there on the last look pauses ("threat"); one the agent already resumed past does not,
     * so a fight is not re-paused every tick by the mob being fought.
     */
    public void threats(com.google.gson.JsonArray now) {
        java.util.Set<String> keys = new java.util.HashSet<>(); boolean fresh = false;
        for (com.google.gson.JsonElement t : now) { String key = t.getAsJsonObject().get("key").getAsString(); keys.add(key); fresh |= !knownThreats.contains(key); }
        knownThreats = keys; threats = now;
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
        lastHealth=null;
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
            "conditions",Json.object("healthDrop",healthDrop,"healthBelow",healthBelow,"airBelow",airBelow,
                "foodBelow",foodBelow,"burning",burning,"threatWithin",threatWithin,
                "actionFailed",actionFailed,"pauseOnDisconnect",pauseOnDisconnect),"threats",threats,"events",events);
    }
}
