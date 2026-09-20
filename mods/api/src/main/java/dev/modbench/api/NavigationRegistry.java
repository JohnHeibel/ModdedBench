// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 ModdedBench contributors
package dev.modbench.api;

/** A single optional provider registered by the standalone Baritone Forge mod. */
public final class NavigationRegistry {
    private static volatile Navigation provider;
    private NavigationRegistry() {}
    public static Navigation get() { return provider; }
    public static void register(Navigation navigation) {
        if (provider != null) throw new IllegalStateException("navigation provider already registered");
        provider = java.util.Objects.requireNonNull(navigation);
    }
}
