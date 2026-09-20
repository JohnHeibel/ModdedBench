// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 Modbench contributors
// Derived from Baritone (https://github.com/cabaletta/baritone), LGPL-3.0-or-later.
package baritone.api.utils;

import baritone.compat.IBlockState;
import net.minecraft.block.Block;
import net.minecraft.item.ItemStack;

/** Native registry identity plus an optional metadata constraint. */
public final class BlockOptionalMeta {
    private final Block block;
    private final Integer meta;
    public BlockOptionalMeta(Block block){this(block,null);}
    public BlockOptionalMeta(Block block,Integer meta){this.block=java.util.Objects.requireNonNull(block);this.meta=meta;if(meta!=null&&(meta<0||meta>15))throw new IllegalArgumentException("world block metadata must be 0..15");}
    public BlockOptionalMeta(String descriptor){
        String[] parts=descriptor.split(":");String id;Integer metadata=null;
        if(parts.length==3){id=parts[0]+":"+parts[1];metadata=Integer.valueOf(parts[2]);}else if(parts.length==1||parts.length==2)id=descriptor;else throw new IllegalArgumentException("invalid block descriptor");
        if(!Block.blockRegistry.containsKey(id))throw new IllegalArgumentException("unknown block: "+id);
        block=(Block)Block.blockRegistry.getObject(id);meta=metadata;
        if(meta!=null&&(meta<0||meta>15))throw new IllegalArgumentException("world block metadata must be 0..15");
    }
    public Block getBlock(){return block;}
    public IBlockState getAnyBlockState(){return IBlockState.of(block,meta==null?0:meta);}
    public boolean matches(IBlockState state){return block==state.getBlock()&&(meta==null||meta==state.meta);}
    public boolean matches(ItemStack stack){return stack!=null&&Block.getBlockFromItem(stack.getItem())==block&&(meta==null||stack.getItem().getMetadata(stack.getItemDamage())==meta);}
}
