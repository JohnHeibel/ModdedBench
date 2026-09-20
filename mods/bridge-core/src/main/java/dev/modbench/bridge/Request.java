// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 Modbench contributors
package dev.modbench.bridge;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** One response, even when disconnect, cancellation and a deadline race completion. */
public final class Request {
    public final JsonElement id;
    public final String method;
    public final JsonObject params;
    public final Session session;
    public final long deadline;
    private final long start = System.nanoTime();
    private final AtomicBoolean done = new AtomicBoolean();
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

    public boolean isDone() { return done.get(); }
    public boolean expired() { return System.nanoTime() >= deadline; }
    public void reply(Object data) { finish(true, Json.GSON.toJsonTree(data)); }
    public void fail(String code, String message) { finish(false, Json.object("code", code, "msg", message)); }

    public void fail(String code,String message,Object receipt) {
        JsonObject error=Json.object("code",code,"msg",message,"receipt",receipt);finish(false,error);
    }
    private void finish(boolean ok, JsonElement value) {
        if (!done.compareAndSet(false, true)) return;
        session.pending.remove(id.toString(), this);
        JsonObject envelope = Json.object("id", id, "ok", ok, "tick", runtime.tick(),
            "seq", runtime.nextSequence(), "src", runtime.side(), "worldEpoch", runtime.worldEpoch(),
            "cost_ms", (System.nanoTime() - start) / 1_000_000.0);
        envelope.add(ok ? "data" : "error", value);
        output.accept(envelope);
    }
}
