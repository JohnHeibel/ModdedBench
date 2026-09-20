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
        Predicate<IBlockState> explicitMiningTargets) {
    public CalculationInputs(World world,BlockStateInterface blocks,ToolSet tools,boolean throwaway,boolean waterPlacement,
            boolean sprint,int frostWalker,int depthStrider,WorldMemory.Snapshot protection,boolean overrideProtection,Predicate<BlockPos> positionAllowed){
        this(world,blocks,tools,throwaway,waterPlacement,sprint,frostWalker,depthStrider,protection,overrideProtection,positionAllowed,s->false);
    }
    public CalculationInputs {
        Objects.requireNonNull(blocks);Objects.requireNonNull(tools);Objects.requireNonNull(protection);Objects.requireNonNull(positionAllowed);
    }
    public static CalculationInputs capture(IBaritone owner,boolean threaded){
        var engine=(Baritone)owner;var ctx=owner.getPlayerContext();var player=ctx.player();var world=ctx.world();
        return new CalculationInputs(world,new BlockStateInterface(ctx,threaded),new ToolSet(player),
            engine.getInventoryBehavior().hasGenericThrowaway(),FallProtection.available()&&!world.provider.isHellWorld,
            player.getFoodStats().getFoodLevel()>6,0,0,ControlRegistry.memory().memory().snapshot(),engine.overrideProtection,engine.positionAllowed,engine.explicitMiningTargets.get());
    }
}
