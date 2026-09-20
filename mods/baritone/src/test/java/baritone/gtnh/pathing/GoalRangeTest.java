// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 ModdedBench contributors
// Derived from Baritone (https://github.com/cabaletta/baritone), LGPL-3.0-or-later.
package baritone.gtnh.pathing;

import baritone.compat.BlockPos;
import org.junit.Test;
import static org.junit.Assert.*;

public class GoalRangeTest {
    @Test public void distantGoalsAreBoundedWithoutCoordinateOverflow() {
        BlockPos start=new BlockPos(-200,90,0);
        assertTrue(GoalRange.validGoal(start,new BlockPos(800,20,1000)));
        assertTrue(GoalRange.validGoal(start,new BlockPos(-200,1,0)));
        assertFalse(GoalRange.validGoal(start,new BlockPos(5000,90,0)));
        assertFalse(GoalRange.validGoal(new BlockPos(Integer.MIN_VALUE,90,0),new BlockPos(Integer.MAX_VALUE,90,0)));
    }
}
