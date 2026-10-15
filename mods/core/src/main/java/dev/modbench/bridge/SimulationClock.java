// SPDX-License-Identifier: MIT
// Copyright (c) 2026 ModdedBench contributors
package dev.modbench.bridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.function.LongSupplier;

/** Server-thread policy. Wall time is recorded separately from simulation time. */
public final class SimulationClock {
    private final LongSupplier nanos;
    private final long started;
    private long changed, pausedNanos, simulationTicks, eventSequence;
    private volatile boolean paused;
    private boolean pauseOnDisconnect = true, news;
    private long fightUntil = -1; private double fightRadius; // a fight job the model started: its hits and its mobs are not news
    private int[] engaged; // while it runs, the mobs after the player, as the host last found them
    private JsonArray threats = new JsonArray();
    private Float lastHealth;
    private JsonObject vitals; // the player as the rules last saw it: a pause ends the tick before the client is sent the hit that caused it
    /** A kind of hurt that keeps coming with no attacker (a burn, a poison, whatever the pack has) is news to health_drop
     *  once: reported, it is kept here with the tick it last hurt, and is news again once it has stopped this long. */
    private static final int LASTS = 100;
    private final java.util.Map<String,Long> lasting = new java.util.HashMap<>();
    private final java.util.Set<String> hurtBy = new java.util.HashSet<>(); // since the last observation: the kinds with no attacker
    private boolean newHurt; // since the last observation: a hit by something, or a kind that is not lasting
    private String reason = "startup";
    private final ArrayDeque<JsonObject> events = new ArrayDeque<>(), told = new ArrayDeque<>();
    /**
     * The rules, the model's table: each watches one kind of danger (at its level, for most) and, when it crosses, freezes
     * the world for the model or, with do "tell", only tells it. The kinds are detected here, every tick; a script that
     * answers a freeze (any other do) is the Python side's, only kept here, as are the model's own checks (interrupts.py).
     */
    static final String KINDS = "health_drop, health_below, air_below, food_below, burning, hostile_within, action_failed";
    /** What a new world starts with; the model edits or deletes any of them. */
    public static JsonArray defaults() {
        return Json.array(Json.object("name","health_drop","kind","health_drop"), Json.object("name","health_below","kind","health_below","level",8),
            Json.object("name","air_below","kind","air_below","level",180), Json.object("name","food_below","kind","food_below","level",6),
            Json.object("name","burning","kind","burning"), Json.object("name","hostile_within","kind","hostile_within","level",12));
    }
    private LinkedHashMap<String,Rule> rules = table(new LinkedHashMap<>(), defaults());
    private JsonObject freeze; // the pause a rule made, while it lasts: no tick runs, and what crosses meanwhile joins it

    /** One rule as the model gave it. A level is reported once as it crosses, and again only after it has recovered. */
    private static final class Rule {
        final JsonObject spec; final String name, kind; final double level; final boolean tell;
        boolean reported;
        final java.util.Map<String,Long> known = new java.util.HashMap<>(); // hostile_within: threat key -> tick last seen
        Rule(JsonObject given) {
            spec=Json.GSON.fromJson(given.toString(),JsonObject.class); name=Json.string(spec,"name",""); kind=Json.string(spec,"kind","");
            if(!name.matches("[a-z][a-z0-9_]{0,48}")) throw new IllegalArgumentException("a rule's name is lower_snake_case, at most 49 characters");
            double max=switch(kind) { case "health_below"->100000; case "air_below"->300; case "food_below"->20; case "hostile_within"->32;
                case "health_drop","burning","action_failed"->-1; default->throw new IllegalArgumentException("kind is one of "+KINDS); };
            if(max>=0!=spec.has("level")) throw new IllegalArgumentException(kind+(max>=0?" needs a level":" takes no level"));
            level=Json.number(spec,"level",-1,0,Math.max(0,max));
            tell=Json.string(spec,"do","ask").equals("tell");
            if(spec.toString().length()>65536) throw new IllegalArgumentException(name+" is over 64 KiB");
        }
        String reason() { return name.equals(kind)?kind:kind+":"+name; }
        JsonObject shown() {
            String does=Json.string(spec,"do","ask");
            JsonObject out=Json.object("name",name,"kind",kind,"do",does.equals("ask")||does.equals("tell")?does:"script");
            if(level>=0) out.addProperty("level",level);
            return out;
        }
    }

    public SimulationClock() { this(System::nanoTime); }
    public SimulationClock(LongSupplier nanos) { this.nanos=nanos; started=changed=nanos.getAsLong(); }
    public boolean paused() { return paused; }
    public long ticks() { return simulationTicks; }
    public String reason() { return reason; }
    public boolean pauseOnDisconnect() { return pauseOnDisconnect; }
    /** True once after a rule told something: the host sends the state at once, not within the second. */
    public boolean news() { boolean was=news; news=false; return was; }
    /** Pauses a running job waits out: a request, a hold, a lost connection or a clock fault. Any other reason is a rule's. */
    private static final java.util.Set<String> WAITED_OUT=java.util.Set.of("requested_pause","client_disconnected",
        "client_unresponsive","agent_disconnected","paused_packet_overflow","clock_protocol_error","step");
    public static boolean waitedOut(String reason) { return WAITED_OUT.contains(reason) || held(reason); }
    /** A hold's pause is named for its holder (operator_hold, backup_hold), so a routine backup never reads as a person stepping in. */
    public static String holdReason(String by) { return by+"_hold"; }
    public static boolean held(String reason) { return reason.endsWith("_hold"); }
    public void pause(String why) {
        if(paused && !waitedOut(reason)) return; // a rule's reason is what the model is told: nothing overwrites it until the resume
        boolean newEvent = !paused || !reason.equals(why);
        transition(true); reason=why;
        if (newEvent) {
            events.add(event("reason", why));
            while(events.size()>32) events.removeFirst();
        }
    }
    private JsonObject event(String key, String value) {
        return Json.object("sequence", ++eventSequence, "tick", simulationTicks, key, value, "wallMs", (nanos.getAsLong()-started)/1_000_000L);
    }
    public void resume() { transition(false); reason="requested_resume"; lastHealth=null; freeze=null; }
    public void tickFinished() {
        if(paused) throw new IllegalStateException("no simulation tick permitted while paused");
        simulationTicks++;
    }
    public void observe(float health, int air) {
        observe(health, air, 20, false);
    }
    /**
     * A level rule reports once, as the value crosses it, and again only after it has recovered: a rule that re-paused
     * every tick while air stayed low would leave no ticks to swim out with. Rules crossing on one tick make one freeze:
     * the first in the table names it, the others join it (its event's with), and all of them are reported. A value that
     * crosses under a pause no rule made (a step's end, the model's own) is not reported, and pauses in its turn.
     * Harm that lasts is one event too. A kind of hurt with no attacker (by damage type: a burn, a poison, a hot item in
     * hand, whatever the pack adds), once it has tripped health_drop, does not trip it again until it has stopped for
     * LASTS ticks; a burning rule's crossing reports the burn's own ticks (onFire, the game's fire timer). A new kind, or a
     * hit by anything, is news: dragon run 3 fell into lava while alight and was not told. Pausing on every tick of a burn
     * made the model switch the hit guard off (dragon run 2, fortress trials 4 and 7).
     */
    public void observe(float health, int air, int food, boolean onFire) {
        boolean hit=lastHealth!=null && health<lastHealth && (newHurt || hurtBy.isEmpty());
        for(Rule r:rules.values()) {
            boolean on=switch(r.kind) { case "health_drop"->hit; case "health_below"->health<=r.level; case "air_below"->air<=r.level;
                case "food_below"->food<=r.level; case "burning"->onFire; default->false; };
            boolean fights=fighting() && (r.kind.equals("health_drop") || r.kind.equals("burning")); // still alight when the fight ends: it crosses then
            if(on && (r.kind.equals("health_drop") || !r.reported) && !fights && open()) crossed(r);
            r.reported&=on;
        }
        hurtBy.clear(); newHurt=false;
        lastHealth=health;
        if(vitals==null || vitals.get("health").getAsFloat()!=health || vitals.get("food").getAsInt()!=food) vitals=Json.object("health",health,"food",food);
    }
    /** Whether a rule may report now: the world runs, or a rule's freeze lasts (no tick has run since it). */
    private boolean open() { return !paused || freeze!=null; }
    private void crossed(Rule r) {
        r.reported=true;
        if(r.kind.equals("health_drop")) for(String k:hurtBy) lasting.put(k,simulationTicks);
        if(r.kind.equals("burning")) lasting.put("onFire",simulationTicks);
        if(r.tell) { told.add(event("rule",r.reason())); while(told.size()>32) told.removeFirst(); news=true; return; }
        if(freeze==null) { pause(r.reason()); freeze=events.peekLast(); freeze.add("with",new JsonArray()); return; }
        JsonArray with=freeze.getAsJsonArray("with");
        for(JsonElement joined:with) if(joined.getAsString().equals(r.reason())) return;
        if(!reason.equals(r.reason())) with.add(new com.google.gson.JsonPrimitive(r.reason()));
    }
    /** A hurt the server saw, by damage type and whether something dealt it, before the observation that shows the health it took. */
    public void hurt(String damageType, boolean attacker) {
        if(attacker) { newHurt=true; return; }
        Long last=lasting.get(damageType);
        if(last!=null && simulationTicks-last<=LASTS) lasting.put(damageType,simulationTicks); else newHurt=true;
        hurtBy.add(damageType);
    }
    /** How far the host looks for mobs after the player: the widest hostile_within level, -1 with none. */
    public double reach() {
        double out=-1; for(Rule r:rules.values()) if(r.kind.equals("hostile_within")) out=Math.max(out,r.level);
        return out;
    }
    /** The mob a fight job was sent after is expected to notice the player: not news for as long as a fight can last. */
    public void expectThreat(int entityId) {
        for(Rule r:rules.values()) if(r.kind.equals("hostile_within")) { r.known.put(String.valueOf(entityId), simulationTicks+6000); r.known.put(entityId+"!", simulationTicks+6000); }
    }
    /**
     * A fight job runs for at most `ticks`: until it ends, taking a hit (health_drop) and being set alight (burning: what
     * the fight costs is health, which its own limits count) are not news, nor is a mob taking the player as its target
     * within `radius` blocks (hostile_within), which is the fight the model chose; by kind, whatever the rules are named.
     * Every other rule stays armed: the levels, a creeper starting to swell, and mobs beyond the radius. 0 ends it.
     */
    public void fight(int ticks, double radius) { fightUntil = ticks > 0 ? simulationTicks + ticks : -1; fightRadius = radius; lastHealth = null; engaged = null; }
    /** While a fight runs, the mobs after the player (the host's word: a client cannot see whom a mob targets); true when that changed, to be sent at once. */
    public boolean engaged(int[] now) { boolean changed = !java.util.Arrays.equals(engaged, now); engaged = now; return changed; }
    public boolean fighting() { return simulationTicks < fightUntil; }
    /**
     * Mobs that are after the player right now, as the server knows them: [{key,...what a player would perceive}]. A
     * hostile_within rule crosses for a key within its level (twice that in line of sight) that it has not seen on its
     * last looks; one it already reported does not, so a fight is not re-paused every tick by the mob being fought.
     */
    public void threats(JsonArray now) {
        threats = now;
        for (Rule r : rules.values()) {
            if (!r.kind.equals("hostile_within")) continue;
            r.known.values().removeIf(seen -> simulationTicks - seen > 200);
            if (!open()) continue; // seen under a pause no rule made: news once the world runs
            boolean fresh = false;
            // A mob that is hit drops its target for a few ticks: it is the same threat when it comes back, not a new one.
            for (JsonElement t : now) {
                JsonObject threat = t.getAsJsonObject(); String key = threat.get("key").getAsString();
                double distance = threat.has("distance") ? threat.get("distance").getAsDouble() : 0;
                if (distance > r.level && !(Json.bool(threat, "lineOfSight", false) && distance <= 2 * r.level)) continue;
                boolean joined = fighting() && !key.endsWith("!") && distance <= fightRadius;
                fresh |= r.known.put(key, simulationTicks) == null && !joined;
            }
            if (fresh) crossed(r);
        }
    }
    /** An action failed (time.report_failure, or the client's word): action_failed rules cross, over a step's end too. */
    public void failed() { for(Rule r:rules.values()) if(r.kind.equals("action_failed") && (open() || waitedOut(reason))) crossed(r); }
    /** time.configure: what is not a rule. pauseOnDisconnect (default true) pauses when the client or the agent's session goes. */
    public void configure(JsonObject p) {
        for(var entry:p.entrySet()) if(!entry.getKey().equals("pauseOnDisconnect") && !entry.getKey().startsWith("_"))
            throw new IllegalArgumentException("configure takes pauseOnDisconnect; "+entry.getKey()+" is not a setting (danger is time.rules)");
        pauseOnDisconnect=Json.bool(p,"pauseOnDisconnect",pauseOnDisconnect);
    }
    /**
     * time.rules: {} reads the whole table; set adds or replaces rules by name (a replaced one keeps its place), remove
     * drops them by name, replace puts a whole table in place. All or nothing. A rule given again unchanged keeps what it
     * has reported; a changed one starts afresh.
     */
    public JsonObject rules(JsonObject p) {
        LinkedHashMap<String,Rule> next=p.has("replace")?table(new LinkedHashMap<>(),p.getAsJsonArray("replace")):new LinkedHashMap<>(rules);
        if(p.has("remove")) for(JsonElement name:p.getAsJsonArray("remove")) next.remove(name.getAsString());
        if(p.has("set")) table(next,p.getAsJsonArray("set"));
        if(next.size()>64) throw new IllegalArgumentException("at most 64 rules");
        rules=next;
        if(reach()<0) threats=new JsonArray(); // the host stops looking: no mob it listed before is still known to be after you
        return Json.object("rules",specs());
    }
    private LinkedHashMap<String,Rule> table(LinkedHashMap<String,Rule> into, JsonArray specs) {
        for(JsonElement spec:specs) {
            if(!spec.isJsonObject()) throw new IllegalArgumentException("a rule is an object {name, kind, level?, do?}");
            Rule r=new Rule(spec.getAsJsonObject()), old=rules==null?null:rules.get(r.name);
            into.put(r.name,old!=null && old.spec.equals(r.spec)?old:r);
        }
        return into;
    }
    private JsonArray specs() { JsonArray out=new JsonArray(); for(Rule r:rules.values()) out.add(r.spec); return out; }
    /** The table as the state every second shows it: what each rule watches and does, without the bodies of scripts. */
    private JsonArray shown() { JsonArray out=new JsonArray(); for(Rule r:rules.values()) out.add(r.shown()); return out; }
    /**
     * What a restarted server needs to come back as it was: the rules, and the pause with its reason. A hold's pause is
     * left out: the hold file is what restores it, and a release must find nothing of its own still paused.
     */
    public JsonObject saved() {
        boolean kept=paused && !held(reason);
        return Json.object("paused",kept,"reason",kept?reason:null,"rules",specs(),"pauseOnDisconnect",pauseOnDisconnect);
    }
    /** The rules of {@link #saved()} (a save without them keeps the defaults); the host pauses again, with the saved reason, once the world may be gated. */
    public void restore(JsonObject saved) {
        if(saved.has("rules")) rules(Json.object("replace",saved.get("rules")));
        pauseOnDisconnect=Json.bool(saved,"pauseOnDisconnect",pauseOnDisconnect);
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
            "rules",shown(),"pauseOnDisconnect",pauseOnDisconnect,"threats",threats,"events",events,"told",told,"vitals",vitals,
            "fight",fighting()?Json.object("untilTick",fightUntil,"radius",fightRadius,"engaged",engaged):null);
    }
}
