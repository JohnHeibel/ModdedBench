// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 Modbench contributors
// Derived from Baritone (https://github.com/cabaletta/baritone), LGPL-3.0-or-later.
package baritone.cache;
import baritone.Baritone;
import baritone.ForgePlanningTestRunner;
import baritone.compat.*;
import org.junit.*;
import java.util.*;
import java.nio.file.*;
import static org.junit.Assert.*;

@org.junit.runner.RunWith(ForgePlanningTestRunner.class)
public class ReferenceCacheTest {
    @BeforeClass public static void bootstrap(){
        cpw.mods.fml.common.Loader.injectData("7","99","40","1614","1.7.10","9.05",new java.io.File("."),List.of());
        net.minecraft.init.Bootstrap.func_151354_b();
    }
    @Before public void defaults(){Baritone.settings().allSettings.forEach(s->s.reset());}
    private NativeChunkSnapshot snapshot() throws Exception {
        var states=new IBlockState[65536];
        for(int y=0;y<=63;y++)for(int z=0;z<16;z++)for(int x=0;x<16;x++)states[y<<8|z<<4|x]=IBlockState.of(Blocks.STONE,0);
        states[63<<8|4<<4|3]=IBlockState.of(net.minecraft.init.Blocks.planks,3);
        states[40<<8|6<<4|5]=IBlockState.of(net.minecraft.init.Blocks.planks,2);
        states[64<<8|8<<4|8]=IBlockState.of(Blocks.WATER,0);
        states[64<<8|8<<4|9]=IBlockState.of(Blocks.FLOWING_WATER,1);
        states[255<<8|15<<4|15]=IBlockState.of(Blocks.GOLD_BLOCK,0);
        BitSet sections=new BitSet(16);sections.set(0,5);sections.set(15);
        var constructor=NativeChunkSnapshot.class.getDeclaredConstructor(int.class,int.class,IBlockState[].class,BitSet.class,BitSet.class,Set.class);
        constructor.setAccessible(true);
        return constructor.newInstance(-1,2,states,sections,new BitSet(),Set.of(net.minecraft.init.Blocks.planks,Blocks.GOLD_BLOCK));
    }
    @Test public void sourcePackerRetainsSurfaceAndTrackedMetadataAtNegativeChunkCoordinates() throws Exception {
        var chunk=ChunkPacker.pack(snapshot());
        assertEquals(3,chunk.getBlock(3,63,4,0).meta);
        assertEquals(2,chunk.getBlock(5,40,6,0).meta);
        assertEquals(List.of(new BlockPos(-11,40,38)),chunk.getAbsoluteBlocks("planks@2"));
        assertEquals(2,chunk.getAbsoluteBlocks("planks").size());
        assertEquals(Blocks.GOLD_BLOCK,chunk.getBlock(15,255,15,0).getBlock());
        assertEquals(Blocks.AIR,chunk.getBlock(0,255,0,0).getBlock());
    }
    @Test public void sourcePackerAvoidsFlowAndAdjacentSourceWater() throws Exception {
        var chunk=ChunkPacker.pack(snapshot());
        assertEquals(Blocks.LAVA,chunk.getBlock(8,64,8,0).getBlock());
        assertEquals(Blocks.LAVA,chunk.getBlock(9,64,8,0).getBlock());
    }
    @Test public void sourceRegionRoundTripPreservesMetadataAndAbsentChunks() throws Exception {
        Path dir=Files.createTempDirectory("baritone-cache-test");
        try{
            var region=new CachedRegion(-1,0,0);region.updateCachedChunk(31,2,ChunkPacker.pack(snapshot()));region.save(dir.toString());
            var loaded=new CachedRegion(-1,0,0);loaded.load(dir.toString());
            assertTrue(loaded.isCached(499,36));assertFalse(loaded.isCached(480,36));
            assertNull(loaded.getBlock(480,63,36));assertNull(loaded.getBlock(499,256,36));
            assertEquals(3,loaded.getBlock(499,63,36).meta);assertEquals(2,loaded.getBlock(501,40,38).meta);
            assertEquals(2,loaded.getLocationsOf("planks").size());
            // The source loader publishes only after parsing the entire file.
            try(var files=Files.list(dir)){Files.write(files.filter(p->!p.toString().endsWith(".tmp")).findFirst().orElseThrow(),new byte[]{1,2,3});}
            assertThrows(IllegalStateException.class,()->loaded.loadStrict(dir.toString()));
            assertEquals(2,loaded.getBlock(501,40,38).meta);
            loaded.load(dir.toString());assertEquals(2,loaded.getBlock(501,40,38).meta);
        }finally{try(var files=Files.walk(dir)){for(var p:files.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(p);}}
    }
    @Test public void registryCodecRejectsUnknownIdsAndInvalidMetadata(){
        assertThrows(IllegalArgumentException.class,()->CacheStateCodec.decode("missing:thing@2"));
        assertThrows(IllegalArgumentException.class,()->CacheStateCodec.decode("planks@16"));
        assertEquals(3,CacheStateCodec.decode("minecraft:planks@3").meta);
    }
    @Test public void failedRegionReplaceKeepsDestinationAndCleansTemporaryFile() throws Exception {
        Path dir=Files.createTempDirectory("baritone-cache-failed-save");
        try {
            var region=new CachedRegion(-1,0,0);region.updateCachedChunk(31,2,ChunkPacker.pack(snapshot()));region.save(dir.toString());
            Path destination;try(var files=Files.list(dir)){destination=files.findFirst().orElseThrow();}
            Files.delete(destination);Files.createDirectory(destination);
            Path sentinel=destination.resolve("preserve");Files.writeString(sentinel,"previous data");
            region.updateCachedChunk(31,2,ChunkPacker.pack(snapshot()));
            assertThrows(IllegalStateException.class,()->region.save(dir.toString()));
            assertEquals("previous data",Files.readString(sentinel));
            try(var files=Files.list(dir)){assertEquals(List.of(destination),files.toList());}
            Files.delete(sentinel);Files.delete(destination);
            region.save(dir.toString());
            var loaded=new CachedRegion(-1,0,0);loaded.load(dir.toString());
            assertEquals(2,loaded.getBlock(501,40,38).meta);
        }finally{try(var files=Files.walk(dir)){for(var p:files.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(p);}}
    }
}
