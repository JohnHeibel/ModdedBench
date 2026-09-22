// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 ModdedBench contributors
// Derived from Baritone (https://github.com/cabaletta/baritone), LGPL-3.0-or-later.
package baritone.gtnh;

import baritone.compat.IBlockState;
import java.util.*;
import net.minecraft.block.Block;
import net.minecraft.client.Minecraft;
import net.minecraft.item.ItemStack;

/** A block is what the game's pick-block says it is (item, damage, NBT): GregTech ores and machines keep their identity in
 *  the tile entity, where block and meta cannot see it. Picked on the game thread; the path search waits a tick for it. */
public final class BlockIdentity {
    private BlockIdentity(){}
    // Blocks without a tile entity are answered once per block and meta, the rest per position (MiningTools.cell).
    private record Key(Block block,int meta,long cell) {}
    private static final Map<Key,Optional<ItemStack>> picked=new java.util.concurrent.ConcurrentHashMap<>();
    private static final Map<Key,int[]> asked=new java.util.concurrent.ConcurrentHashMap<>();
    private static boolean gameThread(){var mc=Minecraft.getMinecraft();return mc!=null&&mc.func_152345_ab();}
    private static ItemStack pick(Key key,int x,int y,int z) {
        var mc=Minecraft.getMinecraft();
        if(mc.theWorld==null||mc.thePlayer==null||!ForgeSnapshot.loaded(mc.theWorld,x,y,z)||mc.theWorld.getBlock(x,y,z)!=key.block())return null;
        try{return WorkAccess.picked(mc.theWorld,new baritone.compat.BlockPos(x,y,z));}catch(RuntimeException|LinkageError failed){return null;}
    }
    /** The picked stack at the state's position, or null when the block picks nothing (or the game did not answer in time). */
    public static ItemStack at(IBlockState state) {
        Key key=new Key(state.getBlock(),state.meta,MiningTools.cell(state.getBlock(),state.meta,state.x,state.y,state.z,true));
        var known=picked.get(key);
        if(known!=null)return known.orElse(null);
        if(gameThread()){var stack=pick(key,state.x,state.y,state.z);picked.put(key,Optional.ofNullable(stack));return stack;}
        if(asked.size()<1024)asked.putIfAbsent(key,new int[]{state.x,state.y,state.z});
        for(long until=System.nanoTime()+200_000_000L;System.nanoTime()<until;) {
            synchronized(picked){try{picked.wait(10);}catch(InterruptedException stop){Thread.currentThread().interrupt();break;}}
            if((known=picked.get(key))!=null)return known.orElse(null);
        }
        return null;
    }
    /** Once a game tick: pick what the path search asked for. */
    static void answer() {
        if(picked.size()>8192)picked.clear();
        if(asked.isEmpty())return;
        int n=0;
        for(var it=asked.entrySet().iterator();it.hasNext()&&n++<512;){var e=it.next();it.remove();int[] p=e.getValue();picked.putIfAbsent(e.getKey(),Optional.ofNullable(pick(e.getKey(),p[0],p[1],p[2])));}
        synchronized(picked){picked.notifyAll();}
    }
}
