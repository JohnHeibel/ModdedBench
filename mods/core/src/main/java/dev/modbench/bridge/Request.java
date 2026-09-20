// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 Modbench contributors
package dev.modbench.bridge;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * One response, even when disconnect, cancellation and a deadline race completion.
 * QUEUED -> RUNNING -> DONE: the game thread claims execution with {@link #start()}; the deadline timer
 * ({@link #expire()}) never answers a RUNNING request, whose synchronous handler reports its real outcome
 * with {@code late: true} when it finishes past the deadline. A handler that returns without answering has
 * started an async job: {@link #detach()} moves it to ASYNC, where the timer answers {@code timeout} as it
 * does for QUEUED and the job's own maintain() pass releases it on the next tick.
 */
public final class Request {
    private static final int QUEUED = 0, RUNNING = 1, ASYNC = 2, DONE = 3;
    public final JsonElement id;
    public final String method;
    public final JsonObject params;
    public final Session session;
    public final long deadline;
    private final long start = System.nanoTime();
    private final AtomicInteger state = new AtomicInteger(QUEUED);
    private final BridgeRuntime runtime;
    private final Consumer<JsonObject> output;

    public Request(JsonElement id, String method, JsonObject params, Session session,
                   BridgeRuntime runtime, Consumer<JsonObject> output) {
        this.id = id;
        this.method = method;
        this.params = params;
        this.session = session;
        this.runtime = runtime;
        this.output = output;
        deadline = start + Json.integer(params, "_timeout_ms", 10000, 1, 3600000) * 1_000_000L;
    }

    public boolean isDone() { return state.get() == DONE; }
    public boolean expired() { return System.nanoTime() >= deadline; }
    /** Game thread: claims execution. False when a timer, cancel or disconnect already answered. */
    public boolean start() { return state.compareAndSet(QUEUED, RUNNING); }
    /** Game thread, after a handler returned without answering: the deadline timer owns the timeout again. */
    public void detach() { if (state.compareAndSet(RUNNING, ASYNC) && expired()) expire(); }
    /** Deadline timer: never answers while a synchronous handler is running. */
    public void expire() { finish(false, Json.object("code", "timeout", "msg", "request deadline elapsed"), true); }
    public void reply(Object data) { finish(true, Json.GSON.toJsonTree(data), false); }
    public void fail(String code, String message) { finish(false, Json.object("code", code, "msg", message), false); }

    public void fail(String code,String message,Object receipt) {
        JsonObject error=Json.object("code",code,"msg",message,"receipt",receipt);finish(false,error,false);
    }
    private void finish(boolean ok, JsonElement value, boolean timer) {
        int previous;
        do {
            previous = state.get();
            if (previous == DONE || timer && previous == RUNNING) return;
        } while (!state.compareAndSet(previous, DONE));
        session.pending.remove(id.toString(), this);
        JsonObject envelope = Json.object("id", id, "ok", ok, "tick", runtime.tick(),
            "seq", runtime.nextSequence(), "src", runtime.side(), "worldEpoch", runtime.worldEpoch(),
            "cost_ms", (System.nanoTime() - start) / 1_000_000.0);
        if (previous == RUNNING && expired()) envelope.addProperty("late", true);
        envelope.add(ok ? "data" : "error", value);
        output.accept(envelope);
    }
}
