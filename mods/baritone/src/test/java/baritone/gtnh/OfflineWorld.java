// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 ModdedBench contributors
package baritone.gtnh;

import net.minecraft.block.Block;
import net.minecraft.client.Minecraft;
import net.minecraft.init.Blocks;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;
import java.lang.reflect.Field;
import java.util.*;

/**
 * Real chunks without a game, for what reads the world on the game thread: stone from y 0 to 79 and air above, answered
 * from chunk storage as the client answers. It is the world of a Minecraft that was never started (no player, no
 * renderer): neither constructor runs. No tile entities, no entities, and only vanilla blocks: what a modded block's own
 * hooks cost is not here.
 */
final class OfflineWorld extends net.minecraft.client.multiplayer.WorldClient {
    Map<Long,Chunk> chunks;
    private OfflineWorld(){super(null,null,0,null,null);}
    static long key(int x,int z){return (long)x&0xffffffffL|(long)z<<32;}
    private static <T>T allocate(Class<T> type) throws Exception {
        Class<?> unsafeClass=Class.forName("sun.misc.Unsafe",true,ClassLoader.getSystemClassLoader());
        Field field=unsafeClass.getDeclaredField("theUnsafe");field.setAccessible(true);
        return type.cast(unsafeClass.getMethod("allocateInstance",Class.class).invoke(field.get(null),type));
    }
    /** The chunks within `radius` chunks of the origin, as Minecraft's world. Call before any class that keeps the Minecraft instance is loaded. */
    static OfflineWorld install(int radius) throws Exception {
        OfflineWorld world=allocate(OfflineWorld.class);world.chunks=new HashMap<>();world.loadedTileEntityList=new ArrayList<>();
        byte stone=(byte)Block.getIdFromBlock(Blocks.stone);
        for(int x=-radius;x<=radius;x++)for(int z=-radius;z<=radius;z++){
            var chunk=new Chunk(null,x,z);var sections=new ExtendedBlockStorage[16];
            for(int s=0;s<5;s++){sections[s]=new ExtendedBlockStorage(s<<4,true);Arrays.fill(sections[s].getBlockLSBArray(),stone);sections[s].removeInvalidBlocks();}
            chunk.setStorageArrays(sections);world.chunks.put(key(x,z),chunk);
        }
        Minecraft mc=allocate(Minecraft.class);mc.theWorld=world;
        Field singleton=Minecraft.class.getDeclaredField("theMinecraft");singleton.setAccessible(true);singleton.set(null,mc);
        return world;
    }
    /** The loaded chunks within `radius` chunks of the origin: (2 radius + 1) squared of them. */
    Map<Long,Chunk> within(int radius){
        Map<Long,Chunk> out=new HashMap<>();
        for(var c:chunks.values())if(Math.abs(c.xPosition)<=radius&&Math.abs(c.zPosition)<=radius)out.put(key(c.xPosition,c.zPosition),c);
        return out;
    }
    void set(int x,int y,int z,Block block){chunks.get(key(x>>4,z>>4)).getBlockStorageArray()[y>>4].func_150818_a(x&15,y&15,z&15,block);}
    @Override public boolean blockExists(int x,int y,int z){return y>=0&&y<256&&chunks.containsKey(key(x>>4,z>>4));}
    @Override public Chunk getChunkFromChunkCoords(int x,int z){return chunks.get(key(x,z));}
    @Override public Block getBlock(int x,int y,int z){var c=y<0||y>255?null:chunks.get(key(x>>4,z>>4));return c==null?Blocks.air:c.getBlock(x&15,y,z&15);}
    @Override public int getBlockMetadata(int x,int y,int z){var c=y<0||y>255?null:chunks.get(key(x>>4,z>>4));return c==null?0:c.getBlockMetadata(x&15,y,z&15);}
    @Override public TileEntity getTileEntity(int x,int y,int z){return null;}
}
