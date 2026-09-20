// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 Modbench contributors
// Derived from Baritone (https://github.com/cabaletta/baritone), LGPL-3.0-or-later.
package baritone.utils;

import baritone.api.utils.IPlayerContext;
import baritone.compat.*;
import baritone.utils.pathing.BetterWorldBorder;
import net.minecraft.block.Block;
import net.minecraft.client.multiplayer.ChunkProviderClient;
import net.minecraft.world.IBlockAccess;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.EmptyChunk;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.biome.BiomeGenBase;
import net.minecraftforge.common.util.ForgeDirection;
import java.util.*;

/** Matches upstream's copied loaded-chunk index and per-search locality cache. */
public final class BlockStateInterface {
    private final Map<Long,Chunk> loadedChunks;
    private final net.minecraft.world.World nativeWorld;
    public final IBlockAccess access;
    public final BetterWorldBorder worldBorder=new BetterWorldBorder();
    public final BlockPos.MutableBlockPos isPassableBlockPos=new BlockPos.MutableBlockPos();
    private Chunk prev;
    private final baritone.api.cache.ICachedWorld cached;
    private final java.util.function.BiPredicate<Integer,Integer> providedLoaded;
    public BlockStateInterface(IBlockAccess blocks,java.util.function.BiPredicate<Integer,Integer> loaded){
        nativeWorld=null;cached=null;loadedChunks=Map.of();access=Objects.requireNonNull(blocks);providedLoaded=Objects.requireNonNull(loaded);
    }
    public BlockStateInterface(IPlayerContext ctx){this(ctx,false);}
    public BlockStateInterface(IPlayerContext ctx,boolean copyLoadedChunks){
        if(!ctx.minecraft().func_152345_ab())throw new IllegalStateException("capture the loaded chunk index on the client thread");
        nativeWorld=ctx.world().nativeWorld;
        providedLoaded=null;
        loadedChunks=LoadedChunkIndex.capture((ChunkProviderClient)nativeWorld.getChunkProvider());
        var data=ctx.worldData();cached=data==null?null:data.getCachedWorld();
        access=new Access();
    }
    private static long key(int x,int z){return (long)x&0xffffffffL|(long)z<<32;}
    private Chunk chunk(int x,int z){if(prev!=null&&prev.xPosition==x>>4&&prev.zPosition==z>>4)return prev;return prev=loadedChunks.get(key(x>>4,z>>4));}
    public boolean worldContainsLoadedChunk(int x,int z){return providedLoaded!=null?providedLoaded.test(x,z):loadedChunks.containsKey(key(x>>4,z>>4));}
    public boolean isLoaded(int x,int z){return worldContainsLoadedChunk(x,z)||cached!=null&&cached.isCached(x,z);}
    public IBlockState get0(BlockPos p){return get0(p.getX(),p.getY(),p.getZ());}
    public IBlockState get0(int x,int y,int z){
        if(providedLoaded!=null)return y<0||y>=256||!providedLoaded.test(x,z)?new IBlockState(Blocks.AIR,0,access,x,y,z):new IBlockState(access.getBlock(x,y,z),access.getBlockMetadata(x,y,z),access,x,y,z);
        Chunk c=y>=0&&y<256?chunk(x,z):null;
        if(c!=null)return new IBlockState(c.getBlock(x&15,y,z&15),c.getBlockMetadata(x&15,y,z&15),access,x,y,z);
        var region=cached==null?null:cached.getRegion(x>>9,z>>9);
        var state=region==null?null:region.getBlock(x&511,y,z&511);
        return new IBlockState(state==null?Blocks.AIR:state.getBlock(),state==null?0:state.meta,access,x,y,z);
    }
    public static IBlockState get(IPlayerContext ctx,BlockPos p){return ctx.world().getBlockState(p);}
    public static Block getBlock(IPlayerContext ctx,BlockPos p){return get(ctx,p).getBlock();}
    private final class Access implements IBlockAccess {
        public Block getBlock(int x,int y,int z){return get0(x,y,z).getBlock();}
        public int getBlockMetadata(int x,int y,int z){return get0(x,y,z).meta;}
        public TileEntity getTileEntity(int x,int y,int z){
            Chunk c=chunk(x,z);if(c==null)return null;
            // Do not call getTileEntityUnsafe: native lookup can create a tile on the search thread.
            return (TileEntity)c.chunkTileEntityMap.get(new net.minecraft.world.ChunkPosition(x&15,y,z&15));
        }
        public int getLightBrightnessForSkyBlocks(int x,int y,int z,int min){return min<<4;}
        public int isBlockProvidingPowerTo(int x,int y,int z,int side){return getBlock(x,y,z).isProvidingStrongPower(this,x,y,z,side);}
        public boolean isAirBlock(int x,int y,int z){return getBlock(x,y,z).isAir(this,x,y,z);}
        public BiomeGenBase getBiomeGenForCoords(int x,int z){return nativeWorld.getBiomeGenForCoords(x,z);}
        public int getHeight(){return nativeWorld.getHeight();}
        public boolean extendedLevelsInChunkCache(){return false;}
        public boolean isSideSolid(int x,int y,int z,ForgeDirection side,boolean fallback){return isLoaded(x,z)?getBlock(x,y,z).isSideSolid(this,x,y,z,side):fallback;}
    }
}
