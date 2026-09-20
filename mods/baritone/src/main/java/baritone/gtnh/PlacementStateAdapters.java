// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 Modbench contributors
// Derived from Baritone (https://github.com/cabaletta/baritone), LGPL-3.0-or-later.
package baritone.gtnh;

import baritone.gtnh.pathing.BlockPos;
import java.util.*;
import net.minecraft.block.*;
import net.minecraft.util.Vec3;
import net.minecraft.world.World;

/** Pure prediction of native onBlockPlacedBy effects, without invoking effects in a real world. */
public final class PlacementStateAdapters {
    @FunctionalInterface public interface Predictor {int metadata(Block block,World world,BlockPos target,int initial,float yaw,Vec3 eye);}
    private static final Map<Class<? extends Block>,Predictor> ADAPTERS=new LinkedHashMap<>();
    static {
        register(BlockStairs.class,(b,w,p,m,y,e)->(m&4)|new int[]{2,1,3,0}[quadrant(y)]);
        register(BlockFurnace.class,(b,w,p,m,y,e)->new int[]{2,5,3,4}[quadrant(y)]);
        register(BlockPumpkin.class,(b,w,p,m,y,e)->(quadrant(y)+2)&3);
        Predictor sixWay=(b,w,p,m,y,e)-> {
            if(Math.abs(e.xCoord-p.x())<2&&Math.abs(e.zCoord-p.z())<2){if(e.yCoord-p.y()>2)return 1;if(p.y()>e.yCoord)return 0;}
            return new int[]{2,5,3,4}[quadrant(y)];
        };
        register(BlockPistonBase.class,sixWay);register(BlockDispenser.class,sixWay);
        register(BlockChest.class,(b,w,p,m,y,e)-> {
            int facing=new int[]{2,5,3,4}[quadrant(y)];boolean ns=w.getBlock(p.x(),p.y(),p.z()-1)==b||w.getBlock(p.x(),p.y(),p.z()+1)==b,ew=w.getBlock(p.x()-1,p.y(),p.z())==b||w.getBlock(p.x()+1,p.y(),p.z())==b;
            return (!ns&&!ew||ns&&(facing==4||facing==5)||ew&&(facing==2||facing==3))?facing:m;
        });
    }
    private PlacementStateAdapters() {}
    public static void register(Class<? extends Block> type,Predictor adapter){ADAPTERS.put(type,Objects.requireNonNull(adapter));}
    static int quadrant(float yaw){return (int)Math.floor(yaw*4.0F/360.0F+.5)&3;}
    public static int predict(Block block,World world,BlockPos target,int initial,float yaw,Vec3 eye) {
        // Use a class's inherited native implementation only when it has not
        // overridden the callback; a mod subclass can register its own adapter.
        try {
            Class<?> owner=null;Class<?>[] args={World.class,int.class,int.class,int.class,net.minecraft.entity.EntityLivingBase.class,net.minecraft.item.ItemStack.class};
            for(String name:List.of("onBlockPlacedBy","func_149689_a"))try{owner=block.getClass().getMethod(name,args).getDeclaringClass();break;}catch(NoSuchMethodException ignored){}
            Predictor adapter=ADAPTERS.get(owner);if(adapter!=null)return adapter.metadata(block,world,target,initial,yaw,eye);
        }catch(SecurityException ignored){}
        return initial;
    }
}
