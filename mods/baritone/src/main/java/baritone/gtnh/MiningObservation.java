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
    long cursor;
    int passes;
    MiningObservation(World world,WorkSpec.Bounds bounds,List<Map<String,Object>> selectors,List<Map<String,Object>> items){
        super(new BlockOptionalMeta[0]);this.world=world;this.bounds=bounds;this.selectors=selectors;this.items=items;
    }
    void tick(){
        long until=System.nanoTime()+2_000_000L;int budget=2048;
        while(cursor<bounds.volume()&&budget-->0&&System.nanoTime()<until){
            BlockPos p=bounds.at(cursor++);
            if(WorkAccess.block(world,p,selectors)&&!world.isAirBlock(p.getX(),p.getY(),p.getZ())&&!ForgeFluids.fluid(world.getBlock(p.getX(),p.getY(),p.getZ()))){
                scanning.put(new baritone.compat.BlockPos(p.getX(),p.getY(),p.getZ()),new IBlockState.StateKey(world.getBlock(p.getX(),p.getY(),p.getZ()),world.getBlockMetadata(p.getX(),p.getY(),p.getZ())));
            }
        }
        if(cursor==bounds.volume()){published=Map.copyOf(scanning);scanning.clear();cursor=0;passes++;}
    }
    static boolean matches(Map<baritone.compat.BlockPos,IBlockState.StateKey> snapshot,IBlockState state){
        return state.key().equals(snapshot.get(new baritone.compat.BlockPos(state.x,state.y,state.z)));
    }
    Predicate<IBlockState> capture(){var snapshot=published;return state->matches(snapshot,state);}
    @Override public boolean has(IBlockState state){return matches(published,state);}
    @Override public boolean has(ItemStack stack){return items.stream().anyMatch(selector->WorkAccess.item(stack,selector));}
    @Override public List<BlockOptionalMeta> blocks(){return published.values().stream().distinct().map(key->new BlockOptionalMeta(key.block(),key.meta())).toList();}
    @Override public List<baritone.compat.BlockPos> observedLocations(){return new ArrayList<>(published.keySet());}
    @Override public boolean acceptsDrop(baritone.compat.BlockPos p){
        return p.getX()>=bounds.min().getX()-3&&p.getX()<=bounds.max().getX()+3
            &&p.getY()>=bounds.min().getY()-3&&p.getY()<=bounds.max().getY()+3
            &&p.getZ()>=bounds.min().getZ()-3&&p.getZ()<=bounds.max().getZ()+3;
    }
}
