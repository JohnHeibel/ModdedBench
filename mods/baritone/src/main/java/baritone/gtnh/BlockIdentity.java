// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 ModdedBench contributors
// Derived from Baritone (https://github.com/cabaletta/baritone), LGPL-3.0-or-later.
package baritone.gtnh;

import baritone.Baritone;
import baritone.compat.IBlockState;
import java.util.*;
import net.minecraft.block.Block;
import net.minecraft.client.Minecraft;
import net.minecraft.item.Item;
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

    /** One blocksToAvoid entry: "modid:block" (every meta), "modid:block:meta", or "item=modid:item[:damage]" for what
     *  pick-block returns (a GregTech ore's material, a machine's type). */
    public record Entry(Block block,Integer meta,Item item,Integer damage) {
        static Entry parse(String text) {
            String s=text.trim();boolean picked=s.startsWith("item=");if(picked)s=s.substring(5);
            String[] parts=s.split(":");String id=parts.length>=2?parts[0]+":"+parts[1]:s;
            Integer number=null;
            if(parts.length==3)try{number=Integer.valueOf(parts[2]);}catch(NumberFormatException bad){throw new IllegalArgumentException("blocksToAvoid meta/damage must be a number: "+text);}
            else if(parts.length!=2)throw new IllegalArgumentException("blocksToAvoid entry must be modid:block[:meta] or item=modid:item[:damage]: "+text);
            if(picked){if(!Item.itemRegistry.containsKey(id))throw new IllegalArgumentException("unknown item in blocksToAvoid: "+id);return new Entry(null,null,(Item)Item.itemRegistry.getObject(id),number);}
            if(!Block.blockRegistry.containsKey(id))throw new IllegalArgumentException("unknown block in blocksToAvoid: "+id);
            return new Entry((Block)Block.blockRegistry.getObject(id),number,null,null);
        }
    }
    private record Parsed(List<String> from,List<Entry> entries,boolean picked) {}
    private static volatile Parsed parsed=new Parsed(null,List.of(),false);
    /** The entries of a setting value, parsed once per change; throws on an entry the game does not know. */
    public static List<Entry> entries(List<String> setting) {
        var p=parsed;
        if(p.from()!=setting){List<Entry> out=new ArrayList<>();for(String s:setting)out.add(Entry.parse(s));p=parsed=new Parsed(setting,List.copyOf(out),out.stream().anyMatch(e->e.item()!=null));}
        return p.entries();
    }
    private static List<Entry> avoid(){try{return entries(Baritone.settings().blocksToAvoid.value);}catch(IllegalArgumentException invalid){return List.of();}}
    /** Avoided by block and meta alone: the part of blocksToAvoid a per-state cache may keep. */
    public static boolean avoidedState(IBlockState state) {
        for(var e:avoid())if(e.block()==state.getBlock()&&(e.meta()==null||e.meta()==state.meta))return true;
        return false;
    }
    /** Avoided by what pick-block says is at this position. */
    public static boolean avoidedAt(IBlockState state) {
        avoid();
        if(!parsed.picked()||state.getBlock().getMaterial()==net.minecraft.block.material.Material.air)return false;
        var stack=at(state);if(stack==null)return false;
        for(var e:avoid())if(e.item()!=null&&e.item()==stack.getItem()&&(e.damage()==null||e.damage()==stack.getItemDamage()))return true;
        return false;
    }
}
