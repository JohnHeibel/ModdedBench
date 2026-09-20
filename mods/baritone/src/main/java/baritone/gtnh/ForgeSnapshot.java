// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 ModdedBench contributors
// Derived from Baritone (https://github.com/cabaletta/baritone), LGPL-3.0-or-later.
package baritone.gtnh;

import baritone.compat.BlockPos;
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

/** Bounded game-thread capture of Forge collision shapes for terrain inspection and work poses. */
final class ForgeSnapshot {
    final World world;
    final int minX,minY,minZ,width,height,depth;
    private final byte[] cells;
    private final float[] flows;
    private final Map<BlockPos,List<CollisionBox>> shapes=new HashMap<>();
    private final Map<BlockPos,LadderFacing> ladders=new HashMap<>();
    private int cursor;
    private ForgeSnapshot(World world,int x,int y,int z,int w,int h,int d) {
        this.world=world;minX=x;minY=y;minZ=z;width=w;height=h;depth=d;
        cells=new byte[width*height*depth];
        flows=new float[cells.length*3];
    }
    private boolean captureSlice() {
        long end=System.nanoTime()+3_000_000L;
        int count=0;
        while(cursor<cells.length && count++<2048 && System.nanoTime()<end) {
            int y=cursor%height+minY, z=(cursor/height)%depth+minZ, x=cursor/(height*depth)+minX;
            Cell cell=sample(world,x,y,z);cells[cursor]=cell.kind();
            BlockPos p=new BlockPos(x,y,z);
            if(cell.kind()==TerrainGrid.PARTIAL || cell.kind()==TerrainGrid.BLOCKED || cell.kind()==TerrainGrid.LADDER) shapes.put(p,cell.boxes());
            if(cell.ladder()!=LadderFacing.NONE) ladders.put(p,cell.ladder());
            if(cells[cursor]==TerrainGrid.WATER) {
                Vec3 f=ForgeFluids.flow(world,x,y,z);
                if(f==null) cells[cursor]=TerrainGrid.UNKNOWN;
                else {flows[cursor*3]=(float)f.xCoord;flows[cursor*3+1]=(float)f.yCoord;flows[cursor*3+2]=(float)f.zCoord;}
            }
            cursor++;
        }
        return cursor==cells.length;
    }
    private TerrainGrid finish() { return new TerrainGrid(minX,minY,minZ,width,height,depth,cells,flows,shapes,ladders); }
    static TerrainGrid local(World world,BlockPos a,BlockPos b) {
        int x=Math.min(a.getX(),b.getX())-1,z=Math.min(a.getZ(),b.getZ())-1,y=Math.max(0,Math.min(a.getY(),b.getY())-2);
        ForgeSnapshot snapshot=new ForgeSnapshot(world,x,y,z,Math.abs(a.getX()-b.getX())+3,Math.min(256,Math.max(a.getY(),b.getY())+5)-y,Math.abs(a.getZ()-b.getZ())+3);
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
        return classify(world,p.getX(),p.getY(),p.getZ())==TerrainGrid.CLEAR
            && classify(world,p.getX(),p.getY()+1,p.getZ())==TerrainGrid.CLEAR
            && classify(world,p.getX(),p.getY()-1,p.getZ())==TerrainGrid.SUPPORT
            && liveClear(world,p.getX()+.5,p.getY(),p.getZ()+.5,p.getY()+1.8);
    }
    static boolean water(World world,BlockPos p) {return classify(world,p.getX(),p.getY(),p.getZ())==TerrainGrid.WATER;}

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
