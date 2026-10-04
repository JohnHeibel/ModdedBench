// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 ModdedBench contributors
// Derived from Baritone (https://github.com/cabaletta/baritone), LGPL-3.0-or-later.
package baritone.gtnh;

import baritone.api.utils.*;
import baritone.compat.IBlockState;
import baritone.compat.BlockPos;
import baritone.gtnh.pathing.*;
import net.minecraft.item.ItemStack;
import net.minecraft.world.World;
import java.util.*;
import java.util.function.Predicate;

/** Native mod callbacks run on the client thread; searches only see immutable matches. */
final class MiningObservation extends BlockOptionalMetaLookup {
    private final World world;
    private final WorkSpec.Bounds bounds;
    private final List<Map<String,Object>> selectors,items;
    private final Map<baritone.compat.BlockPos,IBlockState.StateKey> scanning=new HashMap<>();
    private Map<baritone.compat.BlockPos,IBlockState.StateKey> published=Map.of();
    // The mine process prunes and sorts every location it is offered, several times a second: with a centre it is offered
    // the `wide` matches nearest it, chosen when a pass ends, and more only once it has no use for those.
    private final java.util.function.Supplier<BlockPos> centre;
    private volatile List<BlockPos> near=List.of(); // the receipt counts it from the bridge thread
    private int wide=256;
    long cursor;
    int passes;
    MiningObservation(World world,WorkSpec.Bounds bounds,List<Map<String,Object>> selectors,List<Map<String,Object>> items){this(world,bounds,selectors,items,null);}
    MiningObservation(World world,WorkSpec.Bounds bounds,List<Map<String,Object>> selectors,List<Map<String,Object>> items,java.util.function.Supplier<BlockPos> centre){
        super(new BlockOptionalMeta[0]);this.world=world;this.bounds=bounds;this.selectors=selectors;this.items=items;this.centre=centre;
    }
    void tick(){
        // The first pass is what the job waits for. Every later one only refreshes it, on ticks that also path and dig.
        long until=System.nanoTime()+(passes==0?2_000_000L:250_000L);int budget=passes==0?2048:256;
        while(cursor<bounds.volume()&&budget-->0&&System.nanoTime()<until){
            BlockPos p=bounds.at(cursor++);
            if(WorkAccess.block(world,p,selectors)&&!world.isAirBlock(p.getX(),p.getY(),p.getZ())&&!ForgeFluids.fluid(world.getBlock(p.getX(),p.getY(),p.getZ()))){
                scanning.put(new baritone.compat.BlockPos(p.getX(),p.getY(),p.getZ()),new IBlockState.StateKey(world.getBlock(p.getX(),p.getY(),p.getZ()),world.getBlockMetadata(p.getX(),p.getY(),p.getZ())));
            }
        }
        if(cursor==bounds.volume()){published=Map.copyOf(scanning);scanning.clear();cursor=0;passes++;if(centre!=null)near=nearest(published.keySet(),centre.get(),wide);}
    }
    /** The k positions nearest `centre`, nearest first. */
    static List<BlockPos> nearest(Collection<BlockPos> all,BlockPos centre,int k){
        var kept=new PriorityQueue<BlockPos>(Comparator.comparingDouble((BlockPos p)->centre.distanceSq(p)).reversed());
        for(BlockPos p:all){kept.add(p);if(kept.size()>k)kept.poll();}
        List<BlockPos> out=new ArrayList<>(kept);out.sort(Comparator.comparingDouble(centre::distanceSq));return out;
    }
    /** Offer twice as many matches, nearest the centre as it is now; false when every match was already on offer. */
    boolean widen(){
        if(centre==null||near.size()>=published.size())return false;
        wide*=2;near=nearest(published.keySet(),centre.get(),wide);return true;
    }
    int matches(){return published.size();}
    static boolean matches(Map<baritone.compat.BlockPos,IBlockState.StateKey> snapshot,IBlockState state){
        return state.key().equals(snapshot.get(new baritone.compat.BlockPos(state.x,state.y,state.z)));
    }
    Predicate<IBlockState> capture(){var snapshot=published;return state->matches(snapshot,state);}
    @Override public boolean has(IBlockState state){return matches(published,state);}
    /** With no item selectors, every drop is wanted. */
    @Override public boolean has(ItemStack stack){return items.isEmpty()?stack!=null&&stack.stackSize>0:items.stream().anyMatch(selector->WorkAccess.item(stack,selector));}
    @Override public List<BlockOptionalMeta> blocks(){return published.values().stream().distinct().map(key->new BlockOptionalMeta(key.block(),key.meta())).toList();}
    @Override public List<baritone.compat.BlockPos> observedLocations(){return new ArrayList<>(centre==null?published.keySet():near);}
    @Override public boolean acceptsDrop(baritone.compat.BlockPos p){
        return p.getX()>=bounds.min().getX()-3&&p.getX()<=bounds.max().getX()+3
            &&p.getY()>=bounds.min().getY()-3&&p.getY()<=bounds.max().getY()+3
            &&p.getZ()>=bounds.min().getZ()-3&&p.getZ()<=bounds.max().getZ()+3;
    }
}
