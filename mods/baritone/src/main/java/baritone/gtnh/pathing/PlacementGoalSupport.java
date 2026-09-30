// SPDX-License-Identifier: LGPL-3.0-or-later
package baritone.gtnh.pathing;

import baritone.compat.BlockPos;

/** Stances must be actionable by the source builder's placement scan. */
public final class PlacementGoalSupport {
    private PlacementGoalSupport() {}
    public static boolean actionableHeight(int targetY,int feetY,boolean covered) {
        int dy=targetY-feetY;
        return dy>=-5&&dy<=1&&(dy!=1||covered);
    }
    public static boolean egress(BlockPos target,BlockPos feet,boolean covered) {
        return (feet.getX()!=target.getX()||feet.getZ()!=target.getZ())&&actionableHeight(target.getY(),feet.getY(),covered);
    }
}
