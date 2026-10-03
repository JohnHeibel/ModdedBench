// SPDX-License-Identifier: LGPL-3.0-or-later
package baritone.gtnh.pathing;

import baritone.compat.BlockPos;

/** Stances must be actionable by the source builder's placement scan. */
public final class PlacementGoalSupport {
    private PlacementGoalSupport() {}
    /** How many cells above the feet the scan looks: the ceiling face over the highest one is within
     * native reach of an eye eyeAboveFeet up, and never past the scan's horizontal radius of 5. */
    public static int reachUp(double eyeAboveFeet,double reach) {
        return Math.max(1,Math.min(5,(int)Math.floor(eyeAboveFeet+reach)-1));
    }
    /** Above the feet only a covered cell is actionable: the ceiling supplies the support. */
    public static boolean actionableHeight(int targetY,int feetY,boolean covered,int up) {
        int dy=targetY-feetY;
        return dy>=-5&&dy<=up&&(dy<=0||covered);
    }
    public static boolean egress(BlockPos target,BlockPos feet,boolean covered,int up) {
        return (feet.getX()!=target.getX()||feet.getZ()!=target.getZ())&&actionableHeight(target.getY(),feet.getY(),covered,up);
    }
}
