// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 ModdedBench contributors
package baritone.gtnh;

import baritone.ForgePlanningTestRunner;
import baritone.Planning;
import baritone.api.utils.BlockOptionalMeta;
import baritone.compat.BlockPos;
import net.minecraft.block.Block;
import net.minecraft.init.Blocks;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;
import java.util.*;
import org.junit.*;
import static org.junit.Assert.*;

@org.junit.runner.RunWith(ForgePlanningTestRunner.class)
public class ChunkObservationTest {
    @BeforeClass public static void registries(){Planning.bootstrap();}
    private final Map<Long,Chunk> loaded=new HashMap<>();
    private int looks;
    /** Stone from y 0 to 79. */
    private Chunk chunk(int x,int z){
        var c=new Chunk(null,x,z);var sections=new ExtendedBlockStorage[16];
        for(int s=0;s<5;s++){sections[s]=new ExtendedBlockStorage(s<<4,true);for(int i=0;i<4096;i++)sections[s].func_150818_a(i&15,i>>8,i>>4&15,Blocks.stone);}
        c.setStorageArrays(sections);loaded.put((long)x&0xffffffffL|(long)z<<32,c);return c;
    }
    private static void set(Chunk c,int x,int y,int z,Block block,int meta){
        var s=c.getBlockStorageArray()[y>>4];s.func_150818_a(x&15,y&15,z&15,block);s.setExtBlockMetadata(x&15,y&15,z&15,meta);
    }
    private void ticks(ChunkObservation scan,int n,BlockPos feet){for(int i=0;i<n;i++)scan.tick(()->{looks++;return loaded;},feet);}

    @Test public void theFirstPassReadsEveryLoadedChunkAndAChunkThatLoadsLaterIsReadOnce(){
        var feet=new BlockPos(8,64,8);
        for(int x=-3;x<=3;x++)for(int z=-3;z<=3;z++)chunk(x,z);
        set(loaded.get(0L),5,70,6,Blocks.crafting_table,0);set(chunk(-2,1),1,3,2,Blocks.crafting_table,0);
        var scan=new ChunkObservation(new BlockOptionalMeta(Blocks.crafting_table));
        for(int t=0;t<1000&&scan.passes==0;t++)ticks(scan,1,feet);
        assertEquals(1,scan.passes);assertEquals(1,looks);
        assertEquals(List.of(new BlockPos(5,70,6),new BlockPos(-31,3,18)),scan.observedLocations());
        // Nothing new is loaded: the loaded chunks are listed once in ten ticks and none is read again.
        set(loaded.get(0L),6,70,6,Blocks.crafting_table,0);
        ticks(scan,30,feet);
        assertEquals(4,looks);assertEquals(1,scan.passes);assertEquals(2,scan.observedLocations().size());
        // Walking loads a chunk: its match is offered within the next look, nearest the feet first.
        set(chunk(4,0),0,64,0,Blocks.crafting_table,0);
        ticks(scan,10,new BlockPos(60,64,8));
        assertEquals(2,scan.passes);assertEquals(new BlockPos(64,64,0),scan.observedLocations().get(0));assertEquals(3,scan.observedLocations().size());
    }

    @Test public void metaIsMatchedAndACommonBlockGivesAFewPerChunkNearestTheFeetInHeight(){
        var c=chunk(0,0);set(c,1,10,1,Blocks.wool,3);set(c,2,10,2,Blocks.wool,4);
        var out=new ArrayList<BlockPos>();
        new ChunkObservation(new BlockOptionalMeta(Blocks.wool,4)).scan(c,64,16,out);
        assertEquals(List.of(new BlockPos(2,10,2)),out);
        out.clear();new ChunkObservation(new BlockOptionalMeta(Blocks.stone)).scan(chunk(-1,2),70,16,out);
        assertEquals(16,out.size());
        for(var p:out){assertTrue(p.toString(),p.getY()>=64&&p.getY()<80);assertEquals(-1,p.getX()>>4);assertEquals(2,p.getZ()>>4);}
    }
}
