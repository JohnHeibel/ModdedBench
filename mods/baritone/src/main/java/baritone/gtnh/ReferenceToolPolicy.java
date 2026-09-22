// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 ModdedBench contributors
// Derived from Baritone (https://github.com/cabaletta/baritone), LGPL-3.0-or-later.
package baritone.gtnh;

/** Retains the existing native mod-tool validation at the source engine boundary. */
public final class ReferenceToolPolicy {
    private ReferenceToolPolicy() {}
    public static boolean eligible(net.minecraft.item.ItemStack stack){return MiningTools.rejected(stack)==null;}
    /** The game's break strength for a stack against a block, or null when the game has not answered yet. */
    public static Double strength(net.minecraft.item.ItemStack stack,baritone.compat.IBlockState state,boolean placed){return MiningTools.strength(stack,state.getBlock(),state.meta,state.x,state.y,state.z,placed);}
    /** Called once per game tick, on the game thread. */
    public static void answer(){MiningTools.answer();}
}
