// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 ModdedBench contributors
// Derived from Baritone (https://github.com/cabaletta/baritone), LGPL-3.0-or-later.
package baritone.compat;
import net.minecraft.block.Block;
import net.minecraft.block.BlockLiquid;
import net.minecraft.world.chunk.Chunk;
import java.util.*;
/** Capture native state and fluid callbacks before handing data to a cache worker. */
public final class NativeChunkSnapshot {
    public final int x,z;
    public final Set<Block> tracked;
    private final IBlockState[] states;
    private final BitSet sections,stillWater;
    private NativeChunkSnapshot(int x,int z,IBlockState[] states,BitSet sections,BitSet stillWater,Set<Block> tracked){
        this.x=x;this.z=z;this.states=states;this.sections=sections;this.stillWater=stillWater;this.tracked=Set.copyOf(tracked);
    }
    public static NativeChunkSnapshot capture(Chunk chunk){
        if(!net.minecraft.client.Minecraft.getMinecraft().func_152345_ab())throw new IllegalStateException("capture cache chunks on the client thread");
        IBlockState[] states=new IBlockState[65536];BitSet sections=new BitSet(16),still=new BitSet(65536);
        Map<IBlockState.StateKey,IBlockState> palette=new HashMap<>();
        var storage=chunk.getBlockStorageArray();
        for(int section=0;section<storage.length;section++){
            var data=storage[section];if(data==null)continue;sections.set(section);
            for(int y=0;y<16;y++)for(int z=0;z<16;z++)for(int x=0;x<16;x++){
                Block block=data.getBlockByExtId(x,y,z);int meta=data.getExtBlockMetadata(x,y,z),height=section*16+y,index=height<<8|z<<4|x;
                states[index]=palette.computeIfAbsent(new IBlockState.StateKey(block,meta),key->IBlockState.of(key.block(),key.meta()));
                if((x==0||x==15||z==0||z==15)&&(block==Blocks.WATER||block==Blocks.FLOWING_WATER)&&
                    BlockLiquid.getFlowDirection(chunk.worldObj,chunk.xPosition*16+x,height,chunk.zPosition*16+z,block.getMaterial())==-1000.0)still.set(index);
            }
        }
        return new NativeChunkSnapshot(chunk.xPosition,chunk.zPosition,states,sections,still,baritone.cache.CachedChunk.trackedBlocks());
    }
    public boolean sectionPresent(int y){return sections.get(y);}
    public IBlockState getBlockState(int x,int y,int z){var state=states[y<<8|z<<4|x];return state==null?IBlockState.of(Blocks.AIR,0):state;}
    public boolean stillBoundaryWater(int x,int y,int z){return stillWater.get(y<<8|z<<4|x);}
}
