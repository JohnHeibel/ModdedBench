// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 ModdedBench contributors
package baritone.gtnh;

import baritone.api.pathing.goals.*;
import baritone.compat.BlockPos;
import java.util.*;
import net.minecraft.block.material.Material;
import net.minecraft.client.Minecraft;
import net.minecraft.world.World;

/**
 * The goal of a player who needs air: the nearest cells where the head is out of any liquid, standing on
 * something or floating at the surface of water. Read once from the world when the goal is made.
 */
final class Breathable {
    private Breathable(){}
    static final int MAX=64;
    static Goal goal(int radius){
        Minecraft mc=Minecraft.getMinecraft();World w=mc.theWorld;
        if(w==null||mc.thePlayer==null)throw new IllegalArgumentException("breathable needs a player in a world");
        List<BlockPos> cells=cells(w,WorkAccess.feet(),radius);
        if(cells.isEmpty())throw new IllegalArgumentException("no breathable cell within "+radius+" blocks: dig up, or look further with a larger radius");
        return new Cells(cells);
    }
    /**
     * The cells found when the job began. The world keeps moving (water floods in), so on the game thread, where arrival is
     * decided, a cell is checked again and dropped once it no longer breathes; the planner then heads for the next one.
     */
    static final class Cells implements Goal {
        private final Set<BlockPos> cells=java.util.concurrent.ConcurrentHashMap.newKeySet();
        Cells(List<BlockPos> found){cells.addAll(found);}
        @Override public boolean isInGoal(int x,int y,int z){
            BlockPos p=new BlockPos(x,y,z);
            if(!cells.contains(p))return false;
            Minecraft mc=Minecraft.getMinecraft();
            if(mc.func_152345_ab()&&mc.theWorld!=null&&!breathable(mc.theWorld,x,y,z)){cells.remove(p);return false;}
            return true;
        }
        @Override public double heuristic(int x,int y,int z){
            double best=Double.POSITIVE_INFINITY;
            for(BlockPos c:cells)best=Math.min(best,GoalBlock.calculate(x-c.getX(),y-c.getY(),z-c.getZ()));
            return best;
        }
        @Override public String toString(){return "Breathable{"+cells.size()+" cells}";}
    }
    /** Up to MAX breathable feet cells within radius (above more than below), nearest first. */
    static List<BlockPos> cells(World w,BlockPos from,int radius){
        List<BlockPos> found=new ArrayList<>();
        int down=Math.min(radius,6);
        for(int y=Math.max(1,from.getY()-down);y<=Math.min(254,from.getY()+radius);y++)
            for(int x=from.getX()-radius;x<=from.getX()+radius;x++)
                for(int z=from.getZ()-radius;z<=from.getZ()+radius;z++)
                    if(breathable(w,x,y,z))found.add(new BlockPos(x,y,z));
        found.sort(Comparator.comparingInt(p->sq(p.getX()-from.getX())+sq(p.getY()-from.getY())+sq(p.getZ()-from.getZ())));
        return found.size()>MAX?found.subList(0,MAX):found;
    }
    private static int sq(int d){return d*d;}
    /** Head in open air; feet free, in air or in water at the surface; and something under the feet or water to float in. */
    static boolean breathable(World w,int x,int y,int z){
        Material head=w.getBlock(x,y+1,z).getMaterial(),feet=w.getBlock(x,y,z).getMaterial(),under=w.getBlock(x,y-1,z).getMaterial();
        if(head.isLiquid()||head.blocksMovement()||ForgeFluids.fluid(w.getBlock(x,y+1,z)))return false;
        if(feet.blocksMovement()||feet==Material.fire)return false;
        boolean swimming=feet==Material.water;
        if(feet.isLiquid()&&!swimming)return false;
        return swimming||under.blocksMovement();
    }
}
