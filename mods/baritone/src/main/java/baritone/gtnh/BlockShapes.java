// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 ModdedBench contributors
// Derived from Baritone (https://github.com/cabaletta/baritone), LGPL-3.0-or-later.
package baritone.gtnh;

import baritone.compat.IBlockState;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.block.Block;
import net.minecraft.client.Minecraft;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.world.World;

/** Whether the path search may stand on or walk through a block: the game's own collision boxes for it, not a list of
 *  known blocks. A 1.7 block keeps its bounds in mutable fields, so the boxes are asked on the game thread only; the
 *  search runs on its own thread, reads the answers kept here and queues the ones it lacks for the next game tick. */
public final class BlockShapes {
    private BlockShapes(){}
    /** empty: nothing collides in the cell. standable: the top is within 0.2 of the cell's top, nothing rises above
     *  it, and a box at that top lies under a centred player's footprint. */
    public record Shape(boolean empty,boolean standable) {}
    private static final Shape FAILED=new Shape(false,false);
    private record Key(Block block,int meta,long pos) {}
    private static final Map<Key,Shape> answers=new ConcurrentHashMap<>();
    private static final Map<Key,int[]> asked=new ConcurrentHashMap<>();
    private static volatile Thread gameThread;

    private static Key key(Block b,int meta,int x,int y,int z){
        // A block with a tile entity may take its shape from it (a pipe's connections): its answer is per position.
        return new Key(b,meta,b.hasTileEntity(meta)?(((long)x&0x3FFFFFF)<<38)|(((long)y&0xFFF)<<26)|((long)z&0x3FFFFFF):Long.MIN_VALUE);
    }
    /** The game's answer for this state at its position, or null when there is none yet. */
    public static Shape of(IBlockState s){
        if(gameThread==null||!s.hasAccess())return null;
        Key k=key(s.getBlock(),s.meta,s.x,s.y,s.z);
        Shape known=answers.get(k);
        if(known==null&&Thread.currentThread()==gameThread){ask(k,s.x,s.y,s.z);known=answers.get(k);}
        else if(known==null&&asked.size()<1024)asked.putIfAbsent(k,new int[]{s.x,s.y,s.z});
        return known==FAILED?null:known;
    }
    /** Once a game tick: answer what the search asked since the last one. */
    public static void answer(){
        gameThread=Thread.currentThread();
        if(answers.size()>16384)answers.clear();
        int n=0;
        for(var it=asked.entrySet().iterator();it.hasNext()&&n++<256;){var e=it.next();it.remove();var p=e.getValue();ask(e.getKey(),p[0],p[1],p[2]);}
    }
    /** On the game thread before a search: answer the blocks around the player, so the first search rarely lacks one. */
    public static void warm(World world,int px,int py,int pz){
        gameThread=Thread.currentThread();
        for(int x=px-8;x<=px+8;x++)for(int z=pz-8;z<=pz+8;z++)for(int y=Math.max(0,py-4);y<=Math.min(255,py+4);y++){
            if(!ForgeSnapshot.loaded(world,x,y,z))continue;
            Block b=world.getBlock(x,y,z);Key k=key(b,world.getBlockMetadata(x,y,z),x,y,z);
            if(!answers.containsKey(k))ask(k,x,y,z);
        }
    }
    private static void ask(Key k,int x,int y,int z){
        Minecraft mc=Minecraft.getMinecraft();World world=mc.theWorld;
        // The state may have changed since it was asked, or its chunk be gone: leave it unanswered.
        if(world==null||!ForgeSnapshot.loaded(world,x,y,z)||world.getBlock(x,y,z)!=k.block()||world.getBlockMetadata(x,y,z)!=k.meta())return;
        List<AxisAlignedBB> boxes=new ArrayList<>();
        try{k.block().addCollisionBoxesToList(world,x,y,z,AxisAlignedBB.getBoundingBox(x-1,y-1,z-1,x+2,y+3,z+2),boxes,mc.thePlayer);}
        catch(RuntimeException|LinkageError failed){answers.put(k,FAILED);return;}
        List<double[]> relative=new ArrayList<>();
        for(var b:boxes)relative.add(new double[]{b.minX-x,b.minY-y,b.minZ-z,b.maxX-x,b.maxY-y,b.maxZ-z});
        answers.put(k,classify(relative));
    }
    /** Boxes relative to the cell's corner, as {minX,minY,minZ,maxX,maxY,maxZ}. */
    static Shape classify(List<double[]> boxes){
        double top=Double.NEGATIVE_INFINITY;boolean under=false;
        for(double[] b:boxes)top=Math.max(top,b[4]);
        for(double[] b:boxes)under|=b[4]>=top-1e-5&&b[0]<.8&&b[3]>.2&&b[2]<.8&&b[5]>.2;
        return new Shape(boxes.isEmpty(),!boxes.isEmpty()&&top>=.8&&top<=1+1e-5&&under);
    }
}
