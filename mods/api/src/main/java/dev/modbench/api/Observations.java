// SPDX-License-Identifier: MIT
// Copyright (c) 2026 ModdedBench contributors
package dev.modbench.api;

import java.util.Map;
import java.util.function.Consumer;

/**
 * The bridge's watchable reads (obs.batch) for other mods: a job that checks what a click changed reads the world the
 * way the model does. Game thread only. reply receives the batch result {values:{alias:..}, errors:{alias:..}} as plain
 * maps, lists, strings, numbers and booleans, now for client reads, or a few ticks later when a server read
 * (obs.tile, obs.nbt, obs.waila) is included; on failure it receives {error:{code,msg}}. The returned handle ends the
 * read: a job that ends runs it, and nothing is delivered afterwards.
 */
public interface Observations {
    Runnable read(Map<String,Map<String,Object>> queries,Consumer<Map<String,Object>> reply);
    final class Registry {
        private static volatile Observations provider;
        private Registry() {}
        public static Observations get() {return provider;}
        public static void register(Observations o) {
            if (provider != null) throw new IllegalStateException("observations already registered");
            provider = java.util.Objects.requireNonNull(o);
        }
    }
}
