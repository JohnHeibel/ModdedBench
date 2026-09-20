// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 ModdedBench contributors
package dev.modbench.api;

import java.util.function.Supplier;

/** Native ray tracing with mod-aware targeting context. Results are net.minecraft.util.MovingObjectPosition. */
public interface Targeting {
    <T> T withContext(Supplier<T> action);
    Object refresh();
    /** {@code world} is a net.minecraft.world.World, {@code start}/{@code end} are net.minecraft.util.Vec3. */
    Object trace(Object world,Object start,Object end,boolean liquid,boolean ignoreWithoutBox,boolean returnMiss);
}
