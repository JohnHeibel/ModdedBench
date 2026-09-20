// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 Modbench contributors
// Derived from Baritone (https://github.com/cabaletta/baritone), LGPL-3.0-or-later.
package baritone.compat;

import net.minecraft.block.Block;
import net.minecraft.item.ItemStack;
import net.minecraft.world.World;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.*;

/** Explicit mod crop contracts; unknown growth metadata is never assumed mature. */
public final class NativeFarmAdapters {
    private NativeFarmAdapters(){}
    public static final Map<Block,BiPredicate<World,BlockPos>> harvest=new ConcurrentHashMap<>();
    /** Additional items confirmed suitable for the source farmland planting action. */
    public static volatile Predicate<ItemStack> plantable=stack->false;
    public static volatile Predicate<ItemStack> fertilizer=stack->false;
    public static volatile Predicate<ItemStack> collect=stack->false;
}
