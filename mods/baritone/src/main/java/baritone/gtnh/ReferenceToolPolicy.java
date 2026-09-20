// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 ModdedBench contributors
// Derived from Baritone (https://github.com/cabaletta/baritone), LGPL-3.0-or-later.
package baritone.gtnh;

/** Retains the existing native mod-tool validation at the source engine boundary. */
public final class ReferenceToolPolicy {
    private ReferenceToolPolicy() {}
    public static boolean eligible(net.minecraft.item.ItemStack stack){return MiningTools.rejected(stack)==null;}
}
