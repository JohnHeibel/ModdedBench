// SPDX-License-Identifier: MIT
// Copyright (c) 2026 ModdedBench contributors
package dev.modbench.api;

/**
 * Who the server last said is after the player, while a fight runs: the mobs whose target the player is, and the last to
 * hurt it. A player reads that off how a mob behaves; a client cannot see whom a mob targets.
 */
public final class Engaged {
    private static volatile int[] ids;
    private static volatile long at;
    private Engaged() {}
    public static void set(int[] now) { ids = now.clone(); at = System.nanoTime(); }
    /** The entity ids, or null when no word has come in the last `maxAgeMs`. */
    public static int[] latest(long maxAgeMs) { int[] now = ids; return now != null && System.nanoTime() - at <= maxAgeMs * 1_000_000L ? now : null; }
}
