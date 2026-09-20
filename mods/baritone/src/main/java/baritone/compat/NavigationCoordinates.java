// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 ModdedBench contributors
// Derived from Baritone (https://github.com/cabaletta/baritone), LGPL-3.0-or-later.
package baritone.compat;

/** Public goals name the physical feet cell; upstream uses the cell above a slab. */
public final class NavigationCoordinates {
    private NavigationCoordinates() {}
    /** Upstream's feet convention, also used when evaluating future build poses. */
    public static baritone.api.utils.BetterBlockPos feet(double x,double feetY,double z,java.util.function.Predicate<BlockPos> slabAt){
        var feet=new baritone.api.utils.BetterBlockPos(x,feetY+.1251,z);
        return slabAt.test(feet)?feet.up():feet;
    }
    public static BlockPos goal(World world,BlockPos physical){
        return goal(world,physical,physical);
    }
    public static BlockPos goal(World world,BlockPos physical,BlockPos lastKnown){
        return goal(physical,lastKnown,world.nativeWorld.blockExists(physical.getX(),physical.getY(),physical.getZ())?world.getBlockState(physical):null);
    }
    /** Unknown terrain retains the last observed destination instead of oscillating on chunk unload. */
    public static BlockPos goal(BlockPos physical,BlockPos lastKnown,IBlockState loaded){
        if(loaded==null)return lastKnown;
        return baritone.pathing.movement.MovementHelper.isBottomSlab(loaded)?physical.up():physical;
    }
}
