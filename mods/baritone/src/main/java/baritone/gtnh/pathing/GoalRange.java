// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 Modbench contributors
// Derived from Baritone (https://github.com/cabaletta/baritone), LGPL-3.0-or-later.
package baritone.gtnh.pathing;

/** Bounds a requested goal before any search: world height, world border and 4096 horizontal blocks. */
public final class GoalRange {
    private GoalRange() {}
    public static boolean validGoal(BlockPos start,BlockPos goal) {
        return goal.y()>=1 && goal.y()<=254 && Math.abs((long)goal.x())<=30000000 && Math.abs((long)goal.z())<=30000000
            && Math.abs((long)goal.x()-start.x())+Math.abs((long)goal.z()-start.z())<=4096;
    }
}
