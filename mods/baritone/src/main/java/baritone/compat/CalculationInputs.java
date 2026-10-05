// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 ModdedBench contributors
// Derived from Baritone (https://github.com/cabaletta/baritone), LGPL-3.0-or-later.
package baritone.compat;

import dev.modbench.api.ControlRegistry;
import baritone.Baritone;
import baritone.api.IBaritone;
import baritone.utils.BlockStateInterface;
import baritone.utils.ToolSet;
import dev.modbench.api.WorldMemory;
import java.util.Objects;
import java.util.function.Predicate;

/** Captured native capabilities supplied to the unchanged cost/settings snapshot. */
public record CalculationInputs(World world,BlockStateInterface blocks,ToolSet tools,
        boolean throwaway,boolean waterPlacement,boolean sprint,int frostWalker,int depthStrider,
        WorldMemory.Snapshot protection,boolean overrideProtection,Predicate<BlockPos> positionAllowed,
        Predicate<IBlockState> explicitMiningTargets,baritone.gtnh.pathing.Snags snags,int air,Predicate<BlockPos> editAllowed,
        baritone.gtnh.pathing.Move[] moves,Predicate<BlockPos> named) {
    /** A player's full air supply in ticks (EntityPlayer#getAir with the head out of water). */
    public static final int FULL_AIR=300;
    public CalculationInputs(World world,BlockStateInterface blocks,ToolSet tools,boolean throwaway,boolean waterPlacement,
            boolean sprint,int frostWalker,int depthStrider,WorldMemory.Snapshot protection,boolean overrideProtection,Predicate<BlockPos> positionAllowed,
            Predicate<IBlockState> explicitMiningTargets,baritone.gtnh.pathing.Snags snags,int air,Predicate<BlockPos> editAllowed,baritone.gtnh.pathing.Move[] moves){
        this(world,blocks,tools,throwaway,waterPlacement,sprint,frostWalker,depthStrider,protection,overrideProtection,positionAllowed,explicitMiningTargets,snags,air,editAllowed,moves,p->false);
    }
    public CalculationInputs(World world,BlockStateInterface blocks,ToolSet tools,boolean throwaway,boolean waterPlacement,
            boolean sprint,int frostWalker,int depthStrider,WorldMemory.Snapshot protection,boolean overrideProtection,Predicate<BlockPos> positionAllowed,
            Predicate<IBlockState> explicitMiningTargets,baritone.gtnh.pathing.Snags snags,int air,Predicate<BlockPos> editAllowed){
        this(world,blocks,tools,throwaway,waterPlacement,sprint,frostWalker,depthStrider,protection,overrideProtection,positionAllowed,explicitMiningTargets,snags,air,editAllowed,baritone.gtnh.pathing.MoveRegistry.own());
    }
    public CalculationInputs(World world,BlockStateInterface blocks,ToolSet tools,boolean throwaway,boolean waterPlacement,
            boolean sprint,int frostWalker,int depthStrider,WorldMemory.Snapshot protection,boolean overrideProtection,Predicate<BlockPos> positionAllowed,
            Predicate<IBlockState> explicitMiningTargets,baritone.gtnh.pathing.Snags snags,int air){
        this(world,blocks,tools,throwaway,waterPlacement,sprint,frostWalker,depthStrider,protection,overrideProtection,positionAllowed,explicitMiningTargets,snags,air,p->true);
    }
    public CalculationInputs(World world,BlockStateInterface blocks,ToolSet tools,boolean throwaway,boolean waterPlacement,
            boolean sprint,int frostWalker,int depthStrider,WorldMemory.Snapshot protection,boolean overrideProtection,Predicate<BlockPos> positionAllowed,
            Predicate<IBlockState> explicitMiningTargets,baritone.gtnh.pathing.Snags snags){
        this(world,blocks,tools,throwaway,waterPlacement,sprint,frostWalker,depthStrider,protection,overrideProtection,positionAllowed,explicitMiningTargets,snags,FULL_AIR);
    }
    public CalculationInputs(World world,BlockStateInterface blocks,ToolSet tools,boolean throwaway,boolean waterPlacement,
            boolean sprint,int frostWalker,int depthStrider,WorldMemory.Snapshot protection,boolean overrideProtection,Predicate<BlockPos> positionAllowed){
        this(world,blocks,tools,throwaway,waterPlacement,sprint,frostWalker,depthStrider,protection,overrideProtection,positionAllowed,s->false);
    }
    public CalculationInputs(World world,BlockStateInterface blocks,ToolSet tools,boolean throwaway,boolean waterPlacement,
            boolean sprint,int frostWalker,int depthStrider,WorldMemory.Snapshot protection,boolean overrideProtection,Predicate<BlockPos> positionAllowed,
            Predicate<IBlockState> explicitMiningTargets){
        this(world,blocks,tools,throwaway,waterPlacement,sprint,frostWalker,depthStrider,protection,overrideProtection,positionAllowed,explicitMiningTargets,new baritone.gtnh.pathing.Snags());
    }
    public CalculationInputs {
        Objects.requireNonNull(blocks);Objects.requireNonNull(tools);Objects.requireNonNull(protection);Objects.requireNonNull(positionAllowed);Objects.requireNonNull(snags);Objects.requireNonNull(editAllowed);Objects.requireNonNull(moves);Objects.requireNonNull(named);
    }
    public static CalculationInputs capture(IBaritone owner,boolean threaded){
        var engine=(Baritone)owner;var ctx=owner.getPlayerContext();var player=ctx.player();var world=ctx.world();
        int x=(int)Math.floor(player.posX),y=(int)Math.floor(player.boundingBox.minY),z=(int)Math.floor(player.posZ);
        baritone.gtnh.BlockShapes.warm(world.nativeWorld,x,y,z);
        var tools=new ToolSet(player);tools.warm(x,y,z);
        return new CalculationInputs(world,new BlockStateInterface(ctx,threaded),tools,
            engine.getInventoryBehavior().hasGenericThrowaway(),FallProtection.available()&&!world.provider.isHellWorld,
            player.getFoodStats().getFoodLevel()>6,0,0,ControlRegistry.memory().memory().snapshot(),engine.overrideProtection,engine.positionAllowed,engine.explicitMiningTargets.get(),engine.snags,
            player.getAir(), // the air bar the player sees
            engine.editAllowed,
            baritone.gtnh.pathing.MoveRegistry.capture(ctx),engine.named);
    }
    /** These inputs with other moves: a search that may take an added move, or may not take one of the walker's own. */
    public CalculationInputs withMoves(baritone.gtnh.pathing.Move... moves){
        return new CalculationInputs(world,blocks,tools,throwaway,waterPlacement,sprint,frostWalker,depthStrider,protection,overrideProtection,positionAllowed,explicitMiningTargets,snags,air,editAllowed,moves,named);
    }
}
