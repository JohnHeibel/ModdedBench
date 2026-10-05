// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 ModdedBench contributors
// Derived from Baritone (https://github.com/cabaletta/baritone), LGPL-3.0-or-later.
package baritone.gtnh;

import baritone.api.utils.*;
import baritone.compat.BlockPos;
import baritone.compat.IBlockState;
import net.minecraft.world.chunk.Chunk;
import java.util.*;
import java.util.function.Supplier;

/**
 * A target named by registry id, found in the loaded chunks on the client thread: nearest chunk first and a slice a tick,
 * then each chunk that loads later, once. The source scanner reads every loaded chunk at every target update instead.
 * A chunk is read once: a match placed in it afterwards is not seen.
 */
final class ChunkObservation extends BlockOptionalMetaLookup {
    private static final int PER_CHUNK=16,OFFERED=256,LOOK_TICKS=10;
    private final Set<Long> seen=new HashSet<>();
    private final ArrayDeque<Chunk> pending=new ArrayDeque<>();
    private final List<BlockPos> found=new ArrayList<>();
    private volatile List<BlockPos> near=List.of(); // the receipt counts it from the bridge thread
    private int age;
    int passes;
    ChunkObservation(BlockOptionalMeta block){super(block);}
    void tick(Supplier<Map<Long,Chunk>> loaded,BlockPos feet){
        boolean had=!pending.isEmpty();
        if(!had&&age++%LOOK_TICKS==0){
            var fresh=new ArrayList<Chunk>();for(var e:loaded.get().entrySet())if(seen.add(e.getKey()))fresh.add(e.getValue());
            fresh.sort(Comparator.comparingLong(c->{long dx=c.xPosition-(feet.getX()>>4),dz=c.zPosition-(feet.getZ()>>4);return dx*dx+dz*dz;}));
            pending.addAll(fresh);had=!fresh.isEmpty();
        }
        // The first pass is what the job waits for. Later chunks are read on ticks that also path and walk.
        long until=System.nanoTime()+(passes==0?2_000_000L:250_000L);int before=found.size();
        while(!pending.isEmpty()&&System.nanoTime()<until)scan(pending.poll(),feet.getY(),PER_CHUNK,found);
        if(found.size()>before)near=MiningObservation.nearest(found,feet,OFFERED);
        if(pending.isEmpty()&&(had||passes==0))passes++;
    }
    /** At most max matches of one chunk, the sections nearest the feet first. */
    void scan(Chunk chunk,int feetY,int max,List<BlockPos> out){
        var sections=chunk.getBlockStorageArray();int f=Math.max(0,Math.min(sections.length-1,feetY>>4)),base=out.size();
        for(int d=0;d<sections.length;d++)for(int y=f-d;y<=f+d;y+=Math.max(1,2*d)){
            if(y<0||y>=sections.length||sections[y]==null)continue;
            for(int i=0;i<4096;i++)if(has(IBlockState.of(sections[y].getBlockByExtId(i&15,i>>8,i>>4&15),sections[y].getExtBlockMetadata(i&15,i>>8,i>>4&15)))){
                out.add(new BlockPos(chunk.xPosition<<4|i&15,y<<4|i>>8,chunk.zPosition<<4|i>>4&15));
                if(out.size()-base>=max)return;
            }
        }
    }
    @Override public List<BlockPos> observedLocations(){return new ArrayList<>(near);}
}
