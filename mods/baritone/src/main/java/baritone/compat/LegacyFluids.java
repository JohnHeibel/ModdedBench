// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 Modbench contributors
// Derived from Baritone (https://github.com/cabaletta/baritone), LGPL-3.0-or-later.
package baritone.compat;
import net.minecraft.block.Block;
public final class LegacyFluids {
    private LegacyFluids(){}
    public static boolean isFluid(Block block){return block instanceof net.minecraftforge.fluids.IFluidBlock||block instanceof net.minecraft.block.BlockLiquid||block.getMaterial().isLiquid();}
    public static boolean unsupportedForSwimming(Block block){return isFluid(block)&&block!=Blocks.WATER&&block!=Blocks.FLOWING_WATER;}
}
