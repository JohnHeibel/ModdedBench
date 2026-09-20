// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 Modbench contributors
package dev.modbench.bridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;

/** Game-independent transport contract. All game handlers and control cleanup run on the game's tick thread. */
public abstract class BridgeRuntime {
    public interface Handler { Object call(Request request) throws Exception; }
    private record Method(String name, String description, String effect, Handler handler) {}
    private final Map<String, Method> methods = new LinkedHashMap<>();
    private final ArrayBlockingQueue<Request> queue = new ArrayBlockingQueue<>(512);
    private final ArrayBlockingQueue<Request> urgent = new ArrayBlockingQueue<>(64);
    private final AtomicLong sequence = new AtomicLong();
    private final String side;
    private volatile long tick;
    private volatile long worldEpoch;
    private Object world;
    private boolean sealed;
    private final String bridgeId = java.util.UUID.randomUUID().toString();
    private final java.util.Set<String> watchable = new java.util.HashSet<>();
    private final java.util.Set<String> remoteWatchable = new java.util.HashSet<>();

    protected BridgeRuntime(String side) { this.side = side; }
    public String side() { return side; }
    public long tick() { return tick; }
    public long worldEpoch() { return worldEpoch; }
    public String bridgeId() { return bridgeId; }
    public final boolean readOnly(String name) {Method method=methods.get(name);return method!=null&&method.effect.equals("read");}
    public long nextSequence() { return sequence.incrementAndGet(); }

    protected final void register(String name, String description, String effect, Handler handler) {
        if (sealed || methods.putIfAbsent(name, new Method(name, description, effect, handler)) != null) {
            throw new IllegalStateException("method registry already sealed or duplicate: " + name);
        }
    }

    public final void seal() { sealed = true; }

    protected final void watchable(String... names) {
        for (String name : names) {
            Method m=methods.get(name);
            if(m==null || !m.effect.equals("read")) throw new IllegalArgumentException("not a read: "+name);
            watchable.add(name);
        }
    }

    /** Subclass obs.batch must explicitly route these reads and preserve context. */
    protected final void watchableRemote(String... names) {
        for(String name:names){if(!readOnly(name))throw new IllegalArgumentException("not a read: "+name);remoteWatchable.add(name);}
    }

    /** Only explicitly registered synchronous reads can participate. Never execute an arbitrary handler to discover its effect. */
    protected final JsonObject observeBatch(Request request,Object context) {
        JsonObject queries=request.params.getAsJsonObject("queries");
        if(queries==null || queries.entrySet().size()>16) throw new IllegalArgumentException("queries must contain at most 16 observations");
        JsonObject values=new JsonObject(),errors=new JsonObject();
        for(var entry:queries.entrySet()) {
            try {
                JsonObject q=entry.getValue().getAsJsonObject();String name=Json.string(q,"method","");
                if(!watchable.contains(name)) throw new IllegalArgumentException("method is not a watchable synchronous read: "+name);
                JsonObject params=q.has("params")?q.getAsJsonObject("params"):new JsonObject();
                Request child=new Request(new com.google.gson.JsonPrimitive("observation-"+java.util.UUID.randomUUID()),name,params,request.session,this,ignored->{});
                Object result=methods.get(name).handler.call(child);
                if(result==null) throw new IllegalStateException("watchable handler returned asynchronously");
                values.add(entry.getKey(),Json.GSON.toJsonTree(result));
            } catch(Exception error) {errors.add(entry.getKey(),Json.object("code","observation_failed","msg",error.toString()));}
        }
        return Json.object("values",values,"errors",errors,"context",context,"tick",tick());
    }

    public final JsonArray describe() {
        JsonArray out = new JsonArray();
        for (Method m : methods.values()) out.add(Json.object("name", m.name, "desc", m.description,
            "effect", m.effect, "thread", remoteWatchable.contains(m.name)?"server_observation":side,"watchable",watchable.contains(m.name)||remoteWatchable.contains(m.name)));
        for (String name : new String[]{"sys.ping", "sys.methods", "sys.capabilities", "requests.cancel"}) {
            out.add(Json.object("name", name, "effect", name.equals("requests.cancel") ? "interaction" : "read", "thread", "transport"));
        }
        return out;
    }

    /** Expanded at build time from pack.lock.json (see the core processResources task). */
    static final String PACK = pack();
    private static String pack() {
        try (var in = BridgeRuntime.class.getResourceAsStream("/modbench-pack.properties")) {
            var p = new java.util.Properties(); if (in != null) p.load(in); return p.getProperty("pack", "unknown");
        } catch (java.io.IOException e) { return "unknown"; }
    }
    public Object capabilities() {
        return Json.object("protocol", 1, "version", "0.1.0", "minecraft", "1.7.10", "pack", PACK,
            "side", side, "bridgeId",bridgeId,"baritone", false, "tickControl", false, "methods", describe());
    }

    public final void dispatch(Request r) {
        switch (r.method) {
            case "sys.ping" -> r.reply(Json.object("pong", true, "side", side));
            case "sys.methods" -> r.reply(describe());
            case "sys.capabilities" -> r.reply(capabilities());
            case "requests.cancel" -> {
                JsonElement id = r.params.get("requestId");
                Request target = id == null ? null : r.session.pending.get(id.toString());
                if (target != null && target != r) target.fail("cancelled", "request cancelled by its owner");
                r.reply(Json.object("cancelled", target != null && target != r));
            }
            default -> {
                if (!methods.containsKey(r.method)) r.fail("unknown_method", r.method);
                else if (!(r.method.equals("act.stop") || r.method.equals("interrupt.fire") || r.method.equals("interrupt.ack") || r.method.equals("sys.shutdown") ? urgent : queue).offer(r)) {
                    r.fail("busy", "game-thread request queue is full");
                }
            }
        }
    }

    public final void startTick(Object identity) {
        tick++;
        service(identity);
    }

    /** Service transport and cancellation without advancing simulation time. */
    public final void service(Object identity) {
        if (identity != world) {
            world = identity;
            worldEpoch++;
            controlsChanged("world_changed");
            cancelQueue(queue, "world_changed");
        }
        maintainControls();
        drain(urgent, 64);
        drain(queue, 64);
    }

    public final void simulationTick() { tick++; }

    private void drain(ArrayBlockingQueue<Request> source, int max) {
        long until = System.nanoTime() + 4_000_000;
        for (int i = 0; i < max && System.nanoTime() < until; i++) {
            Request r = source.poll();
            if (r == null) break;
            if (r.isDone()) continue;
            if (!r.session.connected) { r.fail("disconnected", "session closed"); continue; }
            if (r.expired()) { r.fail("timeout", "deadline elapsed before game-thread execution"); continue; }
            if (!r.start()) continue;
            try {
                admit(r);
                if (r.method.equals("act.stop")) cancelQueuedInteractions();
                if (r.method.equals("sys.shutdown")) cancelQueue(queue, "shutdown");
                Object result = methods.get(r.method).handler.call(r);
                if (result != null) r.reply(result); else r.detach();
            } catch (IllegalArgumentException e) {
                r.fail("bad_request", e.getMessage());
            } catch (Exception e) {
                r.fail("game_error", e.toString());
            } catch (LinkageError e) {
                r.fail("linkage_error", "installed runtime API is incompatible with this handler: "+e);
            }
        }
    }

    protected final void cancelQueuedInteractions() {
        // Stop is a barrier: work already waiting must not immediately press keys again.
        // Observations remain available so the caller can inspect the stopped state.
        for (Request pending : queue) {
            Method method = methods.get(pending.method);
            if (!method.effect.equals("read") && queue.remove(pending)) {
                pending.fail("cancelled", "queued interaction cancelled by act.stop");
            }
        }
    }

    private void cancelQueue(ArrayBlockingQueue<Request> source, String reason) {
        Request r;
        while ((r = source.poll()) != null) r.fail(reason, "world changed before request execution");
    }

    public void close() {
        controlsChanged("shutdown");
        cancelQueue(queue, "shutdown");
        cancelQueue(urgent, "shutdown");
    }

    protected abstract void controlsChanged(String reason);
    protected abstract void maintainControls();
    protected void admit(Request request) {}
}
