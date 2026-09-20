// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 ModdedBench contributors
// Derived from Baritone (https://github.com/cabaletta/baritone), LGPL-3.0-or-later.
package baritone.api.utils;

import baritone.compat.IBlockState;
import net.minecraft.block.Block;
import net.minecraft.item.ItemStack;
import java.util.*;

public class BlockOptionalMetaLookup {
    private final List<BlockOptionalMeta> blocks;
    public BlockOptionalMetaLookup(BlockOptionalMeta... blocks){this.blocks=List.of(blocks);}
    public BlockOptionalMetaLookup(Block... blocks){this(Arrays.stream(blocks).map(BlockOptionalMeta::new).toArray(BlockOptionalMeta[]::new));}
    public BlockOptionalMetaLookup(String... blocks){this(Arrays.stream(blocks).map(BlockOptionalMeta::new).toArray(BlockOptionalMeta[]::new));}
    public boolean has(IBlockState state){return blocks.stream().anyMatch(b->b.matches(state));}
    public boolean has(ItemStack stack){return blocks.stream().anyMatch(b->b.matches(stack));}
    public List<BlockOptionalMeta> blocks(){return blocks;}
    /** Native observation adapter supplies immutable, position-specific matches. */
    public List<baritone.compat.BlockPos> observedLocations(){return null;}
    public boolean acceptsDrop(baritone.compat.BlockPos pos){return true;}
}
