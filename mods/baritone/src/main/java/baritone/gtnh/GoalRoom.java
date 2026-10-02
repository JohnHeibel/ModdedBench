// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 ModdedBench contributors
// Derived from Baritone (https://github.com/cabaletta/baritone), LGPL-3.0-or-later.
package baritone.gtnh;

import baritone.api.pathing.goals.*;
import baritone.compat.IBlockState;
import java.util.*;
import net.minecraft.block.Block;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.world.World;

/**
 * Whether a goal has a cell the player could stand in, by the game's own collision boxes, asked before a search that would
 * flood its whole budget looking for one. Certain or silent: a goal whose cells are not few and known, any block near it in
 * a chunk the client has not loaded (a far goal is walked to in segments, as ever), a block whose shape the game would not
 * give, or an obstruction the job may break leaves the goal to the search. The body is the player's 0.6 x 1.8 box at each
 * feet height the cell offers: its floor, or the top of whatever stands in it or up to half a block below (a slab, soul
 * sand, a cauldron's bottom). It needs something under it, unless the job may place a floor or the column holds a fluid
 * or something climbable, as the game says. A cell with no box counts against the body when the movement model keeps
 * the body out of it (a flowing or unswimmable fluid) and cannot break it.
 */
final class GoalRoom {
    private GoalRoom(){}
    /** Whether the client holds the chunk of a cell. */
    interface Loaded {boolean loaded(int x,int y,int z);}
    /** The game's view near the goal. */
    interface Terrain extends Loaded {
        /** The block's collision boxes, {minX,minY,minZ,maxX,maxY,maxZ} each; null when the game would not say. */
        List<double[]> boxes(int x,int y,int z);
        /** The job gets through it: the movement model walks through it (a door it opens), or breaking is allowed and the
         *  game says a stack the job may swing breaks it. */
        boolean clears(int x,int y,int z);
        /** A body can be held here without a floor: a fluid, or a block the game calls climbable. */
        boolean holds(int x,int y,int z);
        /** A cell with no collision box the movement model still keeps the body out of (a flowing or unswimmable fluid, a
         *  hazard) and the job cannot break: no path ends with the body in it. */
        default boolean enters(int x,int y,int z){return true;}
        Map<String,Object> block(int x,int y,int z);
    }
    /** Cells judged at most: a near goal of radius 3 is 123. */
    static final int MAX_CELLS=125;
    private static final double HALF=.3,HEIGHT=1.8,EPS=1e-7;
    private record Shape(List<double[]> boxes,boolean clears,boolean holds){}
    private enum Room {ROOM,BLOCKED,UNSUPPORTED}

    /** The goal's cells when they are few and certain, else null. Exact classes only: a subclass may widen isInGoal. */
    static List<int[]> cells(Goal goal){List<int[]> out=new ArrayList<>();return add(goal,out)&&out.size()<=MAX_CELLS?out:null;}
    private static boolean add(Goal goal,List<int[]> out){
        if(goal==null)return false;
        if(goal.getClass()==GoalBlock.class){var g=(GoalBlock)goal;out.add(new int[]{g.x,g.y,g.z});return true;}
        if(goal.getClass()==GoalTwoBlocks.class){var p=((GoalTwoBlocks)goal).getGoalPos();out.add(new int[]{p.getX(),p.getY(),p.getZ()});out.add(new int[]{p.getX(),p.getY()-1,p.getZ()});return true;}
        if(goal.getClass()==GoalNear.class){
            var p=((GoalNear)goal).getGoalPos();int x=p.getX(),y=p.getY(),z=p.getZ();
            // Its range is private: a sphere that leaves out the cell four along x holds no cell more than three out.
            if(goal.isInGoal(x+4,y,z))return false;
            if(goal.isInGoal(x,y,z))out.add(new int[]{x,y,z});
            for(int dx=-3;dx<=3;dx++)for(int dy=-3;dy<=3;dy++)for(int dz=-3;dz<=3;dz++)
                if((dx|dy|dz)!=0&&goal.isInGoal(x+dx,y+dy,z+dz))out.add(new int[]{x+dx,y+dy,z+dz});
            return true;
        }
        if(goal.getClass()==GoalComposite.class){for(Goal g:((GoalComposite)goal).goals())if(!add(g,out)||out.size()>MAX_CELLS)return false;return true;}
        return false;
    }
    /** Whether every cell of the goal is in a loaded chunk; null when its cells are not known. */
    static Boolean loaded(Goal goal,Loaded terrain){
        var cells=cells(goal);if(cells==null)return null;
        for(int[] c:cells)if(!terrain.loaded(c[0],c[1],c[2]))return false;
        return true;
    }
    /** Null when the goal may have room; else the evidence that it has none: how many cells, and why the first has none.
     *  `placing`: the job may place blocks, so a floor can be made. */
    static Map<String,Object> refusal(Goal goal,Terrain terrain,boolean placing){
        var cells=cells(goal);if(cells==null||cells.isEmpty())return null;
        Map<Long,Shape> seen=new HashMap<>();Room first=null;
        for(int[] c:cells){Room room=room(c[0],c[1],c[2],terrain,seen,placing);if(room==null||room==Room.ROOM)return null;if(first==null)first=room;}
        int x=cells.get(0)[0],y=cells.get(0)[1],z=cells.get(0)[2];
        Map<String,Object> out=new LinkedHashMap<>();out.put("goalCells",cells.size());out.put("cell",List.of(x,y,z));
        if(first==Room.UNSUPPORTED){
            out.put("why","nothing_to_stand_on");
            Map<String,Object> below=new LinkedHashMap<>();below.put("pos",List.of(x,y-1,z));below.putAll(terrain.block(x,y-1,z));out.put("below",below);
            return out;
        }
        out.put("why","no_room_for_the_body");
        // What the body standing on the cell's floor runs into: the cell and the one above first, as the model names them.
        List<Map<String,Object>> obstructions=new ArrayList<>();
        for(int dy=0;dy<=1;dy++){var s=seen.get(key(x,y+dy,z));
            if(s!=null&&s.boxes().isEmpty()&&!terrain.enters(x,y+dy,z)){Map<String,Object> row=new LinkedHashMap<>();row.put("pos",List.of(x,y+dy,z));row.putAll(terrain.block(x,y+dy,z));row.put("pathable",false);obstructions.add(row);}}
        for(int dy:new int[]{0,1,-1,2})obstruction(x,y+dy,z,x,y,z,seen,terrain,obstructions);
        for(int bx=x-1;bx<=x+1;bx++)for(int by=y-1;by<=y+2;by++)for(int bz=z-1;bz<=z+1;bz++)
            if(bx!=x||bz!=z)obstruction(bx,by,bz,x,y,z,seen,terrain,obstructions);
        out.put("obstructions",obstructions.size()>4?obstructions.subList(0,4):obstructions);
        return out;
    }
    private static void obstruction(int bx,int by,int bz,int x,int y,int z,Map<Long,Shape> seen,Terrain terrain,List<Map<String,Object>> out){
        var s=seen.get(key(bx,by,bz));if(s==null||s.clears())return;
        for(double[] b:s.boxes())
            if(b[1]<y+HEIGHT-EPS&&b[4]>y+EPS&&b[0]<x+1+HALF-EPS&&b[3]>x-HALF+EPS&&b[2]<z+1+HALF-EPS&&b[5]>z-HALF+EPS){
                Map<String,Object> row=new LinkedHashMap<>();row.put("pos",List.of(bx,by,bz));row.putAll(terrain.block(bx,by,bz));out.add(row);return;
            }
    }
    private static long key(int x,int y,int z){return baritone.api.utils.BetterBlockPos.longHash(x,y,z);}
    private static Shape shape(int x,int y,int z,Terrain terrain,Map<Long,Shape> seen){
        long k=key(x,y,z);var s=seen.get(k);
        if(s!=null||seen.containsKey(k))return s;
        if(terrain.loaded(x,y,z)){
            var boxes=terrain.boxes(x,y,z);
            if(boxes!=null)s=new Shape(boxes,!boxes.isEmpty()&&terrain.clears(x,y,z),terrain.holds(x,y,z));
        }
        seen.put(k,s);return s;
    }
    /** Room in the cell, none for the body, room only with nothing under it, or null when that is not certain. A block
     *  the job gets through obstructs nothing but still holds the feet up. */
    private static Room room(int x,int y,int z,Terrain terrain,Map<Long,Shape> seen,boolean placing){
        List<double[]> solid=new ArrayList<>(),all=new ArrayList<>();boolean held=placing;
        for(int bx=x-1;bx<=x+1;bx++)for(int by=y-1;by<=y+2;by++)for(int bz=z-1;bz<=z+1;bz++){
            var s=shape(bx,by,bz,terrain,seen);if(s==null)return null;
            all.addAll(s.boxes());if(!s.clears())solid.addAll(s.boxes());
            if(bx==x&&bz==z&&by<=y+1&&s.holds())held=true;
        }
        // The model's own word on the body's cells that have no box: one it keeps the body out of ends every path short.
        for(int by=y;by<=y+1;by++)if(seen.get(key(x,by,z)).boxes().isEmpty()&&!terrain.enters(x,by,z))return Room.BLOCKED;
        List<Double> feet=new ArrayList<>();feet.add((double)y);
        for(double[] b:all)if(b[4]>=y-.5&&b[4]<y+1&&b[4]!=y&&b[0]<x+1&&b[3]>x&&b[2]<z+1&&b[5]>z)feet.add(b[4]);
        boolean fits=false;
        for(double f:feet)if(fits(x,z,f,solid)){fits=true;if(held||supported(x,z,f,all))return Room.ROOM;}
        return fits?Room.UNSUPPORTED:Room.BLOCKED;
    }
    private static boolean supported(int x,int z,double feet,List<double[]> boxes){
        for(double[] b:boxes)if(Math.abs(b[4]-feet)<1e-6&&b[0]<x+1+HALF&&b[3]>x-HALF&&b[2]<z+1+HALF&&b[5]>z-HALF)return true;
        return false;
    }
    /** Whether the body with its feet at `feet` has a centre in the cell's column that no box's footprint, widened by half
     *  the body, holds strictly inside. Only the edges of those footprints can bound a free point, so they are all tried. */
    static boolean fits(int x,int z,double feet,List<double[]> boxes){
        List<double[]> rects=new ArrayList<>();
        for(double[] b:boxes)if(b[1]<feet+HEIGHT-EPS&&b[4]>feet+EPS)rects.add(new double[]{b[0]-HALF,b[2]-HALF,b[3]+HALF,b[5]+HALF});
        if(rects.isEmpty())return true;
        double[] xs=axis(x,rects,0,2),zs=axis(z,rects,1,3);
        for(double px:xs)for(double pz:zs){
            boolean free=true;
            for(double[] r:rects)if(px>r[0]+EPS&&px<r[2]-EPS&&pz>r[1]+EPS&&pz<r[3]-EPS){free=false;break;}
            if(free)return true;
        }
        return false;
    }
    private static double[] axis(int lo,List<double[]> rects,int a,int b){
        TreeSet<Double> edges=new TreeSet<>();edges.add((double)lo);
        for(double[] r:rects)for(double e:new double[]{r[a],r[b]})if(e>lo&&e<lo+1)edges.add(e);
        List<Double> out=new ArrayList<>(edges);double prev=Double.NaN;
        for(double e:edges){if(!Double.isNaN(prev))out.add((prev+e)/2);prev=e;}
        out.add((prev+lo+1)/2);
        return out.stream().mapToDouble(Double::doubleValue).toArray();
    }

    /** The live world, on the game thread. A block is an obstruction only where the movement model agrees: one it walks
     *  through (a door it opens) is none, and with breaking allowed neither is one the search could break (the same stacks
     *  and answers it uses, and blocksToDisallowBreaking); before the game has answered anything, none is. */
    static Terrain of(baritone.Baritone engine,World world,EntityPlayer player,boolean allowBreak){
        var tools=allowBreak&&player instanceof EntityPlayerSP sp?new baritone.utils.ToolSet(sp):null;
        var bsi=new baritone.utils.BlockStateInterface(engine.getPlayerContext());
        return new Terrain(){
            @Override public boolean loaded(int x,int y,int z){return ForgeSnapshot.loaded(world,x,Math.max(0,Math.min(255,y)),z);}
            @Override public List<double[]> boxes(int x,int y,int z){
                if(y<0||y>255)return List.of();
                List<AxisAlignedBB> list=new ArrayList<>();
                try{world.getBlock(x,y,z).addCollisionBoxesToList(world,x,y,z,AxisAlignedBB.getBoundingBox(x-1,y-1,z-1,x+2,y+3,z+2),list,player);}
                catch(RuntimeException|LinkageError unknown){return null;}
                List<double[]> out=new ArrayList<>(list.size());
                for(var a:list)out.add(new double[]{a.minX,a.minY,a.minZ,a.maxX,a.maxY,a.maxZ});
                return out;
            }
            @Override public boolean clears(int x,int y,int z){
                try{if(baritone.pathing.movement.MovementHelper.canWalkThrough(bsi,x,y,z))return true;}catch(RuntimeException|LinkageError unknown){return true;}
                if(!allowBreak)return false;
                if(tools==null||!MiningTools.onGameThread())return true;
                var s=new IBlockState(world.getBlock(x,y,z),world.getBlockMetadata(x,y,z),world,x,y,z);
                return !BlockRules.neverBreak(s)&&tools.getStrVsBlock(s)>0;
            }
            @Override public boolean enters(int x,int y,int z){
                if(y<0||y>255)return true;
                try{if(baritone.pathing.movement.MovementHelper.canWalkThrough(bsi,x,y,z))return true;}catch(RuntimeException|LinkageError unknown){return true;}
                Block b=world.getBlock(x,y,z);
                if(b instanceof net.minecraft.block.BlockLiquid||ForgeFluids.fluid(b))return false; // a fluid is not broken
                if(!allowBreak)return false;
                if(tools==null||!MiningTools.onGameThread())return true;
                var s=new IBlockState(b,world.getBlockMetadata(x,y,z),world,x,y,z);
                return !BlockRules.neverBreak(s)&&tools.getStrVsBlock(s)>0;
            }
            @Override public boolean holds(int x,int y,int z){
                if(y<0||y>255)return false;
                Block b=world.getBlock(x,y,z);
                try{return ForgeFluids.fluid(b)||b.isLadder(world,x,y,z,player);}catch(RuntimeException|LinkageError unknown){return true;}
            }
            @Override public Map<String,Object> block(int x,int y,int z){
                return Map.of("block",String.valueOf(Block.blockRegistry.getNameForObject(world.getBlock(x,y,z))),"meta",world.getBlockMetadata(x,y,z));
            }
        };
    }
}
