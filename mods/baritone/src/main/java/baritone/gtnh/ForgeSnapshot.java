// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 Modbench contributors
// Derived from Baritone (https://github.com/cabaletta/baritone), LGPL-3.0-or-later.
package baritone.gtnh;

import baritone.gtnh.pathing.BlockPos;
import baritone.gtnh.pathing.TerrainGrid;
import baritone.gtnh.pathing.CollisionBox;
import baritone.gtnh.pathing.LadderFacing;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import net.minecraft.block.Block;
import net.minecraft.client.Minecraft;
import net.minecraft.init.Blocks;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.world.World;
import net.minecraft.util.Vec3;

/** Captures Forge collision shapes on the game thread; search never reads a live world. */
final class ForgeSnapshot {
    final World world;
    final int minX,minY,minZ,width,height,depth;
    private final byte[] cells;
    private final float[] flows;
    private final Map<BlockPos,List<CollisionBox>> shapes=new HashMap<>();
    private final Map<BlockPos,LadderFacing> ladders=new HashMap<>();
    private final Map<BlockPos,Double> breakCosts=new HashMap<>();
    private MiningTools.Costs tools;
    private final double stepHeight=Minecraft.getMinecraft().thePlayer.stepHeight;
    private int cursor;
    final boolean localPlan;
    ForgeSnapshot(World world, BlockPos center) {
        this(world,center.x()-24,Math.max(0,center.y()-8),center.z()-24,49,Math.min(256,Math.max(0,center.y()-8)+20)-Math.max(0,center.y()-8),49);
    }
    ForgeSnapshot(World world,BlockPos center,boolean allowBreak) {
        this(world,center);if(allowBreak) tools=new MiningTools.Costs();
    }
    private ForgeSnapshot(World world,int x,int y,int z,int w,int h,int d) {
        this.world=world;minX=x;minY=y;minZ=z;width=w;height=h;depth=d;
        localPlan=w<49 || d<49;
        cells=new byte[width*height*depth];
        flows=new float[cells.length*3];
    }
    static ForgeSnapshot forGoal(World world,BlockPos start,BlockPos goal,boolean allowBreak,boolean wide) {
        if(wide||Math.abs(start.x()-goal.x())>16||Math.abs(start.z()-goal.z())>16||Math.abs(start.y()-goal.y())>8)return new ForgeSnapshot(world,start,allowBreak);
        int x=Math.min(start.x(),goal.x())-4,z=Math.min(start.z(),goal.z())-4,y=Math.max(0,Math.min(start.y(),goal.y())-3);
        ForgeSnapshot snapshot=new ForgeSnapshot(world,x,y,z,Math.abs(start.x()-goal.x())+9,Math.min(256,Math.max(start.y(),goal.y())+5)-y,Math.abs(start.z()-goal.z())+9);
        if(allowBreak)snapshot.tools=new MiningTools.Costs();return snapshot;
    }
    int cellsCaptured(){return cursor;}
    static ForgeSnapshot forGoals(World world,BlockPos start,List<BlockPos> goals,boolean allowBreak,boolean wide) {
        if(goals.size()==1)return forGoal(world,start,goals.get(0),allowBreak,wide);
        int x=start.x(),y=start.y(),z=start.z(),maxX=x,maxY=y,maxZ=z;
        for(BlockPos p:goals){x=Math.min(x,p.x());y=Math.min(y,p.y());z=Math.min(z,p.z());maxX=Math.max(maxX,p.x());maxY=Math.max(maxY,p.y());maxZ=Math.max(maxZ,p.z());}
        if(wide||maxX-x>40||maxZ-z>40||maxY-y>16)return new ForgeSnapshot(world,start,allowBreak);
        int bottom=Math.max(0,y-3);
        ForgeSnapshot snapshot=new ForgeSnapshot(world,x-4,bottom,z-4,maxX-x+9,Math.min(256,maxY+5)-bottom,maxZ-z+9);
        if(allowBreak)snapshot.tools=new MiningTools.Costs();return snapshot;
    }
    boolean captureSlice() {
        long end=System.nanoTime()+3_000_000L;
        int count=0;
        while(cursor<cells.length && count++<2048 && System.nanoTime()<end) {
            int y=cursor%height+minY, z=(cursor/height)%depth+minZ, x=cursor/(height*depth)+minX;
            Cell cell=sample(world,x,y,z);cells[cursor]=cell.kind();
            BlockPos p=new BlockPos(x,y,z);
            if(cell.kind()==TerrainGrid.PARTIAL || cell.kind()==TerrainGrid.BLOCKED || cell.kind()==TerrainGrid.LADDER) shapes.put(p,cell.boxes());
            if(cell.ladder()!=LadderFacing.NONE) ladders.put(p,cell.ladder());
            if(tools!=null && cell.kind()==TerrainGrid.SUPPORT) {
                double ticks=tools.at(world,p);if(Double.isFinite(ticks)) breakCosts.put(p,ticks);
            }
            if(cells[cursor]==TerrainGrid.WATER) {
                Vec3 f=ForgeFluids.flow(world,x,y,z);
                if(f==null) cells[cursor]=TerrainGrid.UNKNOWN;
                else {flows[cursor*3]=(float)f.xCoord;flows[cursor*3+1]=(float)f.yCoord;flows[cursor*3+2]=(float)f.zCoord;}
            }
            cursor++;
        }
        return cursor==cells.length;
    }
    TerrainGrid finish() { return new TerrainGrid(minX,minY,minZ,width,height,depth,cells,flows,shapes,ladders,stepHeight); }
    Map<BlockPos,Double> breakCosts() {return Map.copyOf(breakCosts);}
    static TerrainGrid local(World world,BlockPos a,BlockPos b) {
        int x=Math.min(a.x(),b.x())-1,z=Math.min(a.z(),b.z())-1,y=Math.max(0,Math.min(a.y(),b.y())-2);
        ForgeSnapshot snapshot=new ForgeSnapshot(world,x,y,z,Math.abs(a.x()-b.x())+3,Math.min(256,Math.max(a.y(),b.y())+5)-y,Math.abs(a.z()-b.z())+3);
        while(!snapshot.captureSlice()) { /* Small, bounded main-thread corridor capture. */ }
        return snapshot.finish();
    }
    private record Cell(byte kind,List<CollisionBox> boxes,LadderFacing ladder) {}
    static boolean loaded(World world,int x,int y,int z) {
        // In 1.7.10 ChunkProviderClient.chunkExists() always returns true.
        // Missing chunks are EmptyChunk placeholders, not observed air.
        return y>=0 && y<256 && world.blockExists(x,y,z) && !world.getChunkFromChunkCoords(x>>4,z>>4).isEmpty();
    }
    static byte classify(World world,int x,int y,int z) {
        return sample(world,x,y,z).kind();
    }
    private static Cell simple(byte kind) {return new Cell(kind,List.of(),LadderFacing.NONE);}
    private static Cell sample(World world,int x,int y,int z) {
        if (!loaded(world,x,y,z)) return simple(TerrainGrid.UNKNOWN);
        Block b=world.getBlock(x,y,z);
        if (ForgeFluids.water(b)) return simple(TerrainGrid.WATER);
        if (ForgeFluids.fluid(b) || b==Blocks.fire || b==Blocks.cactus
                || b==Blocks.web || b==Blocks.soul_sand) return simple(TerrainGrid.HAZARD);
        List<AxisAlignedBB> boxes=new ArrayList<>();
        b.addCollisionBoxesToList(world,x,y,z,AxisAlignedBB.getBoundingBox(x-1,y-1,z-1,x+2,y+3,z+2),boxes,Minecraft.getMinecraft().thePlayer);
        List<CollisionBox> copied=boxes.stream().map(box->new CollisionBox(box.minX,box.minY,box.minZ,box.maxX,box.maxY,box.maxZ)).toList();
        if(b.isLadder(world,x,y,z,Minecraft.getMinecraft().thePlayer)) {
            LadderFacing facing=LadderFacing.NONE;
            for(CollisionBox box:copied) {
                if(box.maxX()<=x+.25) facing=LadderFacing.WEST;
                else if(box.minX()>=x+.75) facing=LadderFacing.EAST;
                else if(box.maxZ()<=z+.25) facing=LadderFacing.NORTH;
                else if(box.minZ()>=z+.75) facing=LadderFacing.SOUTH;
            }
            return new Cell(facing==LadderFacing.NONE?TerrainGrid.BLOCKED:TerrainGrid.LADDER,copied,facing);
        }
        if(boxes.isEmpty()) return simple(TerrainGrid.CLEAR);
        // Tall or overhanging shapes remain collision obstacles, not candidate footing.
        if(boxes.stream().anyMatch(box -> box.maxY>y+1.00001 || box.minX<x || box.maxX>x+1 || box.minZ<z || box.maxZ>z+1))
            return new Cell(TerrainGrid.BLOCKED,copied,LadderFacing.NONE);
        if(boxes.size()==1) {
            AxisAlignedBB box=boxes.get(0);
            if(box.minX==x && box.minY==y && box.minZ==z && box.maxX==x+1 && box.maxY==y+1 && box.maxZ==z+1) return simple(TerrainGrid.SUPPORT);
        }
        return new Cell(TerrainGrid.PARTIAL,copied,LadderFacing.NONE);
    }
    static boolean liveStandable(World world,BlockPos p) {
        return classify(world,p.x(),p.y(),p.z())==TerrainGrid.CLEAR
            && classify(world,p.x(),p.y()+1,p.z())==TerrainGrid.CLEAR
            && classify(world,p.x(),p.y()-1,p.z())==TerrainGrid.SUPPORT
            && liveClear(world,p.x()+.5,p.y(),p.z()+.5,p.y()+1.8);
    }
    static boolean water(World world,BlockPos p) {return classify(world,p.x(),p.y(),p.z())==TerrainGrid.WATER;}
    static boolean passable(World world,int x,int y,int z) {
        byte c=classify(world,x,y,z); return c==TerrainGrid.CLEAR||c==TerrainGrid.WATER||c==TerrainGrid.LADDER;
    }
    static boolean liveSwimmable(World world,BlockPos p) {
        int x=p.x(),y=p.y(),z=p.z();
        if(!water(world,p)||!passable(world,x,y+1,z)||!passable(world,x,y+2,z)) return false;
        Vec3 flow=ForgeFluids.flow(world,x,y,z);
        if(flow==null || (flow.yCoord<-.5 && classify(world,x,y-1,z)!=TerrainGrid.SUPPORT)) return false;
        for(int[] d:new int[][]{{1,0},{-1,0},{0,1},{0,-1}}) for(int h=-1;h<=1;h++) {
            byte c=classify(world,x+d[0],y+h,z+d[1]);
            if(c==TerrainGrid.HAZARD || c==TerrainGrid.UNKNOWN) return false;
        }
        return liveClear(world,x+.5,y,z+.5,y+2.3);
    }
    static boolean liveTraversable(World world,BlockPos p) {return liveStandable(world,p)||liveSwimmable(world,p);}

    /** Check the swept body volume, including liquids whose collision box is empty. */
    static boolean safeBody(World world,double x,double y,double z,boolean allowWater) {
        for(int bx=(int)Math.floor(x-.31);bx<=Math.floor(x+.31);bx++)
            for(int bz=(int)Math.floor(z-.31);bz<=Math.floor(z+.31);bz++)
                for(int by=(int)Math.floor(y+.01);by<=Math.floor(y+1.79);by++) {
                    byte c=classify(world,bx,by,bz);
                    if(c==TerrainGrid.HAZARD||c==TerrainGrid.UNKNOWN||(!allowWater&&c==TerrainGrid.WATER)) return false;
                }
        return true;
    }
    static boolean liveClear(World world,double x,double minY,double z,double maxY) {
        // Match the actual player's width. Check the complete body, including
        // neighboring collision boxes and entities that the block-grid snapshot cannot represent.
        return world.getCollidingBoundingBoxes(Minecraft.getMinecraft().thePlayer,
            AxisAlignedBB.getBoundingBox(x-.3,minY+.001,z-.3,x+.3,maxY-.001,z+.3)).isEmpty();
    }
}
