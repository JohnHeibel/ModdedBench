// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 ModdedBench contributors
// Derived from Baritone (https://github.com/cabaletta/baritone), LGPL-3.0-or-later.
package baritone.compat;

import net.minecraft.block.*;
import java.util.*;

/** Explicit metadata property masks. Unregistered mod metadata is always compared exactly. */
public final class LegacyStateProperties {
    private record Properties(Map<String,Integer> fields,int orientation){}
    private static final Map<Class<?>,Properties> PROPERTIES=new HashMap<>();
    static {
        register(BlockStairs.class,Map.of("facing",3,"half",4),7);
        register(BlockOldLog.class,Map.of("variant",3,"axis",12),12);
        register(BlockNewLog.class,Map.of("variant",3,"axis",12),12);
        register(BlockTrapDoor.class,Map.of("facing",3,"open",4,"half",8),12);
        register(BlockFurnace.class,Map.of("facing",7),7);
        register(BlockChest.class,Map.of("facing",7),7);
        register(BlockLadder.class,Map.of("facing",7),7);
    }
    private LegacyStateProperties(){}
    public static boolean hasOrientation(Block block){
        var p=PROPERTIES.get(block.getClass());
        // Native single slabs choose their half from the clicked face/height.
        // Keep this placement fact separate from upstream buildIgnoreDirection,
        // whose ignored property set does not include the slab half.
        boolean singleSlab=(block.getClass()==BlockStoneSlab.class||block.getClass()==BlockWoodSlab.class)&&!block.isOpaqueCube();
        return singleSlab||p!=null&&p.orientation()!=0;
    }
    public static void register(Class<? extends Block> type,Map<String,Integer> fields,int orientation){
        PROPERTIES.put(type,new Properties(Map.copyOf(fields),orientation));
    }
    public static boolean same(IBlockState first,IBlockState second,boolean ignoreDirection,List<String> ignored){
        if(first.getBlock()!=second.getBlock())return false;
        Properties p=PROPERTIES.get(first.getBlock().getClass());int mask=15;
        if(p!=null){if(ignoreDirection)mask&=~p.orientation();for(String name:ignored)mask&=~p.fields().getOrDefault(name,0);}
        return (first.meta&mask)==(second.meta&mask);
    }
    public static IBlockState substitute(IBlockState source,Block block){
        // Metadata numbers are not portable between arbitrary blocks. Copy only registered common properties.
        Properties from=PROPERTIES.get(source.getBlock().getClass()),to=PROPERTIES.get(block.getClass());int meta=0;
        if(from!=null&&to!=null)for(var field:from.fields().entrySet()){
            Integer target=to.fields().get(field.getKey());
            if(target!=null&&target.equals(field.getValue()))meta|=source.meta&target;
        }
        return IBlockState.of(block,meta);
    }
}
