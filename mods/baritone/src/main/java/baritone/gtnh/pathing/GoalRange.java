// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 ModdedBench contributors
// Derived from Baritone (https://github.com/cabaletta/baritone), LGPL-3.0-or-later.
package baritone.gtnh.pathing;

import baritone.compat.BlockPos;
/** Bounds a requested goal before any search: world height, world border and 4096 horizontal blocks. */
public final class GoalRange {
    private GoalRange() {}
    public static boolean validGoal(BlockPos start,BlockPos goal) {
        return goal.getY()>=1 && goal.getY()<=254 && Math.abs((long)goal.getX())<=30000000 && Math.abs((long)goal.getZ())<=30000000
            && Math.abs((long)goal.getX()-start.getX())+Math.abs((long)goal.getZ()-start.getZ())<=4096;
    }
}
