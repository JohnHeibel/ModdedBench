// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 ModdedBench contributors
// Derived from Baritone (https://github.com/cabaletta/baritone), LGPL-3.0-or-later.
package baritone.gtnh;

import baritone.compat.IBlockState;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import net.minecraft.block.Block;
import net.minecraft.client.Minecraft;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.world.World;

/** Whether the path search may stand on or walk through a block: the game's own collision boxes for it, not a list of
 *  known blocks. A 1.7 block keeps its bounds in mutable fields, so the boxes are asked on the game thread only; the
 *  search runs on its own thread, reads the answers kept here and queues the ones it lacks for the next game tick. */
public final class BlockShapes {
    private BlockShapes(){}
    /** empty: nothing collides in the cell. standable: the top is at least 0.875 (a lower top puts the player's feet,
     *  floor(minY+0.1251), in this cell), nothing rises above it, and a box at that top lies under a centred player's
     *  footprint. top: the highest box top; surface: the {minX,minZ,maxX,maxZ} of each box at that top. */
    public record Shape(boolean empty,boolean standable,double top,double[][] surface) {
        /** One box covers the whole top: a player anywhere over the cell stands on it. */
        public boolean full(){for(double[] r:surface)if(r[0]<=1e-5&&r[1]<=1e-5&&r[2]>=1-1e-5&&r[3]>=1-1e-5)return true;return false;}
        /** The top lies inside the cell, above 0.1251 and under 0.875, at most `feetAbove` over its bottom: a player on it
         *  (not beside it) stands within this cell, and the engine's feet cell is the one above, as upstream's for a slab. */
        public boolean floorInside(double feetAbove){return !empty&&top>.1251&&top<.875&&top<=feetAbove+1e-3;}
        /** The top is under a whole block's: a body on it reaches down into the cells beside it. */
        public boolean low(){return !empty&&top<1-1e-5;}
        /** A 0.6 footprint centred at (x,z), relative to the cell's corner and possibly beyond it, overlaps the top. */
        public boolean supports(double x,double z){for(double[] r:surface)if(r[0]<x+.3&&r[2]>x-.3&&r[1]<z+.3&&r[3]>z-.3)return true;return false;}
    }
    private static final Shape FAILED=new Shape(false,false,Double.NaN,new double[0][]);
    // A block's answer, one per block and meta, in an array by id<<4|meta: bounded by the registry, never dropped, read
    // without allocating. Ids past the array or metas past 15 (an extended-id pack) go to a map instead.
    private static final int IDS=1<<15;
    private static final AtomicReferenceArray<Shape> states=new AtomicReferenceArray<>(IDS<<4);
    private static final Map<Long,Shape> overflow=new ConcurrentHashMap<>();
    // A block with a tile entity may take its shape from it (a pipe's connections), so its answer is per position, in a
    // direct-mapped table: a new answer takes its slot, so reading, writing and forgetting are O(1) and it never grows.
    private record Placed(long pos,Block block,int meta,Shape shape) {}
    private static final int SLOTS=1<<14;
    private static final AtomicReferenceArray<Placed> placed=new AtomicReferenceArray<>(SLOTS);
    // What the search asked for: a bounded queue, deduplicated per state and per position slot; full means asked later.
    private record Ask(Block block,int meta,int x,int y,int z) {}
    private static final ArrayBlockingQueue<Ask> asked=new ArrayBlockingQueue<>(4096);
    private static final AtomicIntegerArray askedState=new AtomicIntegerArray(IDS<<4);
    private static final AtomicLongArray askedPos=new AtomicLongArray(SLOTS);
    private static final long NONE=pos(0,-1,0); // y 4095: no real position
    static{for(int i=0;i<SLOTS;i++)askedPos.set(i,NONE);}
    private static volatile Thread gameThread;
    /** The game-thread time the queued asks and the warm-up may take per tick, together. */
    public static final long TICK_BUDGET_NS=500_000;
    private static long budgetEnd;

    private static long pos(int x,int y,int z){return (((long)x&0x3FFFFFF)<<38)|(((long)y&0xFFF)<<26)|((long)z&0x3FFFFFF);}
    private static int slot(long pos){return (int)((pos*0x9E3779B97F4A7C15L)>>>50);}
    private static int index(Block b,int meta){int id=Block.getIdFromBlock(b);return id>=0&&id<IDS&&meta>=0&&meta<16?id<<4|meta:-1;}
    private static long wide(Block b,int meta){return (long)Block.getIdFromBlock(b)<<32|meta&0xFFFFFFFFL;}
    private static Shape get(Block b,int meta,int x,int y,int z){
        if(b.hasTileEntity(meta)){long p=pos(x,y,z);Placed e=placed.get(slot(p));return e!=null&&e.pos()==p&&e.block()==b&&e.meta()==meta?e.shape():null;}
        int i=index(b,meta);return i>=0?states.get(i):overflow.get(wide(b,meta));
    }
    private static void put(Block b,int meta,int x,int y,int z,Shape s){
        if(b.hasTileEntity(meta)){long p=pos(x,y,z);placed.set(slot(p),new Placed(p,b,meta,s));return;}
        int i=index(b,meta);if(i>=0)states.set(i,s);else overflow.put(wide(b,meta),s);
    }
    /** The game's answer for this state at its position, or null when there is none yet: the search then treats the
     *  block as unknown (its own fallback, never cached) and the game thread measures it by the next tick. */
    public static Shape of(IBlockState s){
        if(gameThread==null||!s.hasAccess())return null;
        Block b=s.getBlock();
        Shape known=get(b,s.meta,s.x,s.y,s.z);
        // The executor needs it now: measured here, one block's boxes, outside the tick budget.
        if(known==null&&Thread.currentThread()==gameThread){ask(b,s.meta,s.x,s.y,s.z);known=get(b,s.meta,s.x,s.y,s.z);}
        else if(known==null)queue(b,s.meta,s.x,s.y,s.z);
        return known==FAILED?null:known;
    }
    private static void queue(Block b,int meta,int x,int y,int z){
        if(b.hasTileEntity(meta)){long p=pos(x,y,z);int k=slot(p);if(askedPos.getAndSet(k,p)!=p&&!asked.offer(new Ask(b,meta,x,y,z)))askedPos.compareAndSet(k,p,NONE);return;}
        int i=index(b,meta);if(i>=0&&!askedState.compareAndSet(i,0,1))return;
        if(!asked.offer(new Ask(b,meta,x,y,z))&&i>=0)askedState.set(i,0);
    }
    /** Once a game tick: answer what the search asked since the last one, then go on warming, within TICK_BUDGET_NS. */
    public static void answer(){
        gameThread=Thread.currentThread();budgetEnd=System.nanoTime()+TICK_BUDGET_NS;
        for(Ask a;System.nanoTime()<budgetEnd&&(a=asked.poll())!=null;){
            if(a.block().hasTileEntity(a.meta())){long p=pos(a.x(),a.y(),a.z());askedPos.compareAndSet(slot(p),p,NONE);}
            else{int i=index(a.block(),a.meta());if(i>=0)askedState.set(i,0);}
            if(get(a.block(),a.meta(),a.x(),a.y(),a.z())==null)ask(a.block(),a.meta(),a.x(),a.y(),a.z());
        }
        warmStep();
    }
    /** On the game thread when a block changes: its per-position answer, and its neighbours' (a pipe reconnects), go. */
    public static void changed(int x,int y,int z){
        forget(x,y,z);forget(x+1,y,z);forget(x-1,y,z);forget(x,y+1,z);forget(x,y-1,z);forget(x,y,z+1);forget(x,y,z-1);
    }
    private static void forget(int x,int y,int z){long p=pos(x,y,z);int k=slot(p);Placed e=placed.get(k);if(e!=null&&e.pos()==p)placed.compareAndSet(k,e,null);}
    // The warm-up: rings round the player out to WARM_R, WARM_Y up and down, as many cells a tick as the budget leaves,
    // resumed where the last tick stopped. Game thread only.
    private static final int WARM_R=16,WARM_Y=6;
    private static int warmX,warmY=-1,warmZ,warmRing,warmK,warmDy;
    private static boolean warmDone=true;
    private static long warmedAt;
    /** On the game thread before a search: warm the blocks around the player, nearest rings first, over the next ticks.
     *  A context is made every tick, so a warm-up stands while the player stays within four blocks of its centre, and
     *  for five seconds once done (a tile entity's answer may lose its slot meanwhile). Costs nothing itself. */
    public static void warm(World world,int px,int py,int pz){
        gameThread=Thread.currentThread();
        if(warmY>=0&&Math.abs(px-warmX)<4&&Math.abs(py-warmY)<4&&Math.abs(pz-warmZ)<4&&(!warmDone||System.nanoTime()-warmedAt<5_000_000_000L))return;
        warmX=px;warmY=py;warmZ=pz;warmRing=0;warmK=0;warmDy=-WARM_Y;warmDone=false;
    }
    /** The k-th of ring r's 8r cells (one for r 0), as an offset from its centre, going round the square's four sides. */
    static int ringX(int r,int k){if(r==0)return 0;int side=k/(2*r),off=k%(2*r);return side==0?off-r:side==1?r:side==2?r-off:-r;}
    static int ringZ(int r,int k){if(r==0)return 0;int side=k/(2*r),off=k%(2*r);return side==0?-r:side==1?off-r:side==2?r:r-off;}
    private static void warmStep(){
        Minecraft mc=Minecraft.getMinecraft();World world=mc==null?null:mc.theWorld;
        if(warmDone||world==null)return;
        while(System.nanoTime()<budgetEnd){
            int x=warmX+ringX(warmRing,warmK),z=warmZ+ringZ(warmRing,warmK),y=warmY+warmDy;
            if(y>=0&&y<=255&&ForgeSnapshot.loaded(world,x,y,z)){
                Block b=world.getBlock(x,y,z);int meta=world.getBlockMetadata(x,y,z);
                if(get(b,meta,x,y,z)==null)ask(b,meta,x,y,z);
            }
            if(++warmDy<=WARM_Y)continue;
            warmDy=-WARM_Y;
            if(++warmK<(warmRing==0?1:8*warmRing))continue;
            warmK=0;
            if(++warmRing>WARM_R){warmDone=true;warmedAt=System.nanoTime();return;}
        }
    }
    /** On the game thread, when the player is snagged: every cell whose collision boxes touch the player's, measured
     *  again (the cached answer may be stale), as {pos, block, boxes} for the receipt. The player touches these. */
    public static List<Map<String,Object>> touching(net.minecraft.entity.Entity player){
        gameThread=Thread.currentThread();
        World world=player.worldObj;AxisAlignedBB near=player.boundingBox.expand(.05,0,.05);
        List<Map<String,Object>> out=new ArrayList<>();
        for(int x=(int)Math.floor(near.minX);x<=Math.floor(near.maxX);x++)for(int z=(int)Math.floor(near.minZ);z<=Math.floor(near.maxZ);z++)
            for(int y=(int)Math.floor(near.minY)-1;y<=Math.floor(near.maxY);y++){
                if(!ForgeSnapshot.loaded(world,x,y,z))continue;
                Block b=world.getBlock(x,y,z);List<AxisAlignedBB> boxes=new ArrayList<>();
                try{b.addCollisionBoxesToList(world,x,y,z,near,boxes,player);}catch(RuntimeException|LinkageError failed){continue;}
                if(boxes.isEmpty())continue;
                ask(b,world.getBlockMetadata(x,y,z),x,y,z);
                Map<String,Object> row=new LinkedHashMap<>();row.put("pos",List.of(x,y,z));row.put("block",String.valueOf(Block.blockRegistry.getNameForObject(b)));
                row.put("boxes",boxes.stream().limit(4).map(a->List.of(round(a.minX),round(a.minY),round(a.minZ),round(a.maxX),round(a.maxY),round(a.maxZ))).toList());
                out.add(row);
            }
        return out;
    }
    /** On the game thread: whether this cell's collision boxes, measured now, cross the 0.6-wide lane a player walking
     *  through its centre along x (or z) sweeps. An open door lies along the lane's side; a shut one crosses it. */
    public static boolean blocksLane(World world,int x,int y,int z,boolean alongX){
        AxisAlignedBB lane=alongX?AxisAlignedBB.getBoundingBox(x,y,z+.2,x+1,y+1,z+.8):AxisAlignedBB.getBoundingBox(x+.2,y,z,x+.8,y+1,z+1);
        List<AxisAlignedBB> boxes=new ArrayList<>();
        try{world.getBlock(x,y,z).addCollisionBoxesToList(world,x,y,z,lane,boxes,Minecraft.getMinecraft().thePlayer);}
        catch(RuntimeException|LinkageError failed){return true;}
        return !boxes.isEmpty();
    }
    /** On the game thread: where across its way a body has room in a cell it enters along x (or z) with its feet at y.
     *  The game's own collision boxes for everything a body there could touch, the cells beside it included, less what
     *  it steps over: {lo, hi} for the body's middle. An open door's leaf takes a strip of its cell's side, so the
     *  room's middle is not the cell's. Null when the cell has no room, or the game would not say. */
    public static double[] room(World world,net.minecraft.entity.Entity body,int x,int y,int z,boolean alongX){
        AxisAlignedBB sweep=alongX?AxisAlignedBB.getBoundingBox(x,y,z-1,x+1,y+body.height,z+2):AxisAlignedBB.getBoundingBox(x-1,y,z,x+2,y+body.height,z+1);
        List<double[]> across=new ArrayList<>();
        try{
            for(Object o:world.getCollidingBoundingBoxes(body,sweep)){
                AxisAlignedBB b=(AxisAlignedBB)o;
                if(b.maxY>y+body.stepHeight+1e-3)across.add(alongX?new double[]{b.minZ,b.maxZ}:new double[]{b.minX,b.maxX});
            }
        }catch(RuntimeException|LinkageError failed){return null;}
        return gap(across,(alongX?z:x)+.5,body.width/2);
    }
    /** What the spans leave for a body's middle once each is widened by half the body: of the stretches within a cell
     *  either side of `mid`, the one nearest it. Null when that one does not reach the cell `mid` is the middle of. */
    static double[] gap(List<double[]> spans,double mid,double half){
        spans.sort(Comparator.comparingDouble(s->s[0]));
        double[] best=null;double from=mid-1.5+half,end=mid+1.5-half;
        for(int i=0;i<=spans.size();i++){
            double to=Math.min(end,i<spans.size()?spans.get(i)[0]-half:end);
            if(to-from>1e-6&&(best==null||away(from,to,mid)<away(best[0],best[1],mid)))best=new double[]{from,to};
            if(i<spans.size())from=Math.max(from,spans.get(i)[1]+half);
        }
        return best!=null&&best[1]>mid-.5&&best[0]<mid+.5?best:null;
    }
    private static double away(double lo,double hi,double mid){return mid<lo?lo-mid:mid>hi?mid-hi:0;}
    /** Where in its room a body heads for: the cell's middle, or as near it as stays in the cell and a tenth of a block
     *  off the room's edges. */
    public static double aim(double[] room,double mid){
        if(room==null)return mid;
        double lo=Math.max(room[0],mid-.5),hi=Math.min(room[1],mid+.5),m=Math.min(.1,(hi-lo)/2);
        return Math.max(lo+m,Math.min(hi-m,mid));
    }
    private static double round(double v){return Math.round(v*1000)/1000.0;}
    private static void ask(Block block,int meta,int x,int y,int z){
        Minecraft mc=Minecraft.getMinecraft();World world=mc.theWorld;
        // The state may have changed since it was asked, or its chunk be gone: leave it unanswered.
        if(world==null||!ForgeSnapshot.loaded(world,x,y,z)||world.getBlock(x,y,z)!=block||world.getBlockMetadata(x,y,z)!=meta)return;
        List<AxisAlignedBB> boxes=new ArrayList<>();
        try{block.addCollisionBoxesToList(world,x,y,z,AxisAlignedBB.getBoundingBox(x-1,y-1,z-1,x+2,y+3,z+2),boxes,mc.thePlayer);}
        catch(RuntimeException|LinkageError failed){put(block,meta,x,y,z,FAILED);return;}
        List<double[]> relative=new ArrayList<>();
        for(var b:boxes)relative.add(new double[]{b.minX-x,b.minY-y,b.minZ-z,b.maxX-x,b.maxY-y,b.maxZ-z});
        put(block,meta,x,y,z,classify(relative));
    }
    /** Boxes relative to the cell's corner, as {minX,minY,minZ,maxX,maxY,maxZ}. */
    static Shape classify(List<double[]> boxes){
        double top=Double.NEGATIVE_INFINITY;
        for(double[] b:boxes)top=Math.max(top,b[4]);
        List<double[]> surface=new ArrayList<>();
        for(double[] b:boxes)if(b[4]>=top-1e-5)surface.add(new double[]{b[0],b[2],b[3],b[5]});
        Shape shape=new Shape(boxes.isEmpty(),false,top,surface.toArray(new double[0][]));
        return new Shape(shape.empty(),!boxes.isEmpty()&&top>=.875-1e-5&&top<=1+1e-5&&shape.supports(.5,.5),top,shape.surface());
    }
}
