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
    /** Whether the placement can be clicked with the body at x,z. */
    @FunctionalInterface public interface Probe {boolean at(double x,double z);}
    /** How far off a block centre a promised stance must still work: one pixel, well inside the 0.2 a centred body keeps
     * from the cells beside it. */
    public static final double NUDGE=1/16.0;
    /** Whether a stance probed at its block centre is one the player can use. The player comes to rest somewhere in the
     * block, not on its centre, so the click must also work a pixel off it, along x and along z. Two directions, because
     * a view that exists only from the exact centre lies in one plane through the eye (the native tracer passes a ray
     * along an exact diagonal through the seam between two blocks that touch only at an edge), and unless that seam is
     * level with the eye at most one of the two nudges stays in the plane. */
    public static boolean steady(double x,double z,Probe probe) {
        return probe.at(x,z)&&probe.at(x+NUDGE,z)&&probe.at(x,z+NUDGE);
    }
}
