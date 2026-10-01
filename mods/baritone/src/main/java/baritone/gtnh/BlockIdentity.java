// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 ModdedBench contributors
// Derived from Baritone (https://github.com/cabaletta/baritone), LGPL-3.0-or-later.
package baritone.gtnh;

import baritone.compat.IBlockState;
import java.util.*;
import java.util.concurrent.atomic.AtomicReferenceArray;
import net.minecraft.block.Block;
import net.minecraft.client.Minecraft;
import net.minecraft.item.ItemStack;

/** A block is what the game's pick-block says it is (item, damage, NBT): GregTech ores and machines keep their identity in
 *  the tile entity, where block and meta cannot see it. Picked on the game thread; the path search never waits for it: it
 *  queues what it lacks for the next game tick and, until then, is told the game has not answered. */
public final class BlockIdentity {
    private BlockIdentity(){}
    // Blocks without a tile entity are answered once per block and meta, the rest per position (MiningTools.cell), in a
    // direct-mapped table: a new answer takes its slot, nothing is wiped at once and it never grows. Written on the game
    // thread only.
    private record Key(Block block,int meta,long cell) {}
    private record Picked(Key key,ItemStack stack) {}
    /** Who picks: pick() below, or a test standing in for it. Empty: the block picks nothing; null: no answer now (the
     *  block changed or its chunk went), so nothing is kept. */
    interface Picker {Optional<ItemStack> pick(Block block,int x,int y,int z);}
    static volatile Picker game=BlockIdentity::pick;
    static final int SLOTS=1<<15,ASKED=4096;
    private static final AtomicReferenceArray<Picked> picked=new AtomicReferenceArray<>(SLOTS);
    private static final Map<Key,int[]> asked=new java.util.concurrent.ConcurrentHashMap<>();
    private static Key key(Block block,int meta,int x,int y,int z){return new Key(block,meta,MiningTools.cell(block,meta,x,y,z,true));}
    private static int slot(Key k){return (int)((k.hashCode()*0x9E3779B97F4A7C15L)>>>49);}
    private static Picked known(Key k){var e=picked.get(slot(k));return e!=null&&e.key().equals(k)?e:null;}
    private static void put(Key k,Optional<ItemStack> stack){picked.set(slot(k),new Picked(k,stack.orElse(null)));}
    private static Optional<ItemStack> pick(Block block,int x,int y,int z) {
        var mc=Minecraft.getMinecraft();
        if(mc.theWorld==null||mc.thePlayer==null||!ForgeSnapshot.loaded(mc.theWorld,x,y,z)||mc.theWorld.getBlock(x,y,z)!=block)return null;
        try{return Optional.ofNullable(WorkAccess.picked(mc.theWorld,new baritone.compat.BlockPos(x,y,z)));}catch(RuntimeException|LinkageError failed){return Optional.empty();}
    }
    /** The stack pick-block gives at the state's position, empty when it picks nothing. Null only off the game thread,
     *  before the game has answered: the caller takes that as the worst case (a hazard rule as a hazard). */
    public static Optional<ItemStack> at(IBlockState state) {
        Key key=key(state.getBlock(),state.meta,state.x,state.y,state.z);
        var known=known(key);
        if(known!=null)return Optional.ofNullable(known.stack());
        if(MiningTools.onGameThread()){var p=game.pick(key.block(),state.x,state.y,state.z);if(p==null)return Optional.empty();put(key,p);return p;}
        if(asked.size()<ASKED&&!asked.containsKey(key))asked.putIfAbsent(key,new int[]{state.x,state.y,state.z});
        return null;
    }
    /** Game thread, warming: pick this cell now unless it is known. */
    static void warm(Block block,int meta,int x,int y,int z) {
        Key key=key(block,meta,x,y,z);
        if(known(key)==null){var p=game.pick(block,x,y,z);if(p!=null)put(key,p);}
    }
    /** Game thread: pick what the path search asked for, until `end` (System.nanoTime). */
    static void answer(long end) {
        for(var it=asked.entrySet().iterator();it.hasNext()&&System.nanoTime()<end;) {
            var e=it.next();it.remove();var k=e.getKey();int[] p=e.getValue();
            if(known(k)!=null)continue;
            var stack=game.pick(k.block(),p[0],p[1],p[2]);if(stack!=null)put(k,stack);
        }
    }
    static int pending(){return asked.size();}
    /** Tests: forget every pick and ask. */
    static void reset(){for(int i=0;i<SLOTS;i++)picked.set(i,null);asked.clear();}
}
