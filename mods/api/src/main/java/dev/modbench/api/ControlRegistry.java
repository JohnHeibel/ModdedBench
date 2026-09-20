// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 Modbench contributors
package dev.modbench.api;

/** Client control providers registered once by the core mod. */
public final class ControlRegistry {
    private static volatile Controls controls;
    private static volatile Targeting targeting;
    private static volatile PlacementInfo placement;
    private static volatile MemoryAccess memory;
    private ControlRegistry() {}
    public static void register(Controls c,Targeting t,PlacementInfo p,MemoryAccess m) {
        if (controls != null) throw new IllegalStateException("controls already registered");
        controls=java.util.Objects.requireNonNull(c);targeting=java.util.Objects.requireNonNull(t);
        placement=java.util.Objects.requireNonNull(p);memory=java.util.Objects.requireNonNull(m);
    }
    public static Controls controls() { return require(controls); }
    public static Targeting targeting() { return require(targeting); }
    public static PlacementInfo placement() { return require(placement); }
    public static MemoryAccess memory() { return require(memory); }
    private static <T> T require(T provider) {
        if (provider == null) throw new IllegalStateException("modbench core controls are not registered");
        return provider;
    }
}
