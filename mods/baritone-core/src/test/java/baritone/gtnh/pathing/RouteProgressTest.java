// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 Modbench contributors
// Derived from Baritone (https://github.com/cabaletta/baritone), LGPL-3.0-or-later.
package baritone.gtnh.pathing;

import org.junit.Test;
import static org.junit.Assert.*;

public class RouteProgressTest {
    @Test public void hundredsOfBlocksAndVerticalContinuationHaveNoSixteenSegmentCutoff() {
        RouteProgress progress=new RouteProgress();
        for(int i=1;i<=80;i++) assertTrue(progress.advance(new BlockPos(i*8,Math.max(20,200-i*3),0)));
        assertEquals(80,progress.segments());
    }
    @Test public void repeatedFrontierCycleStops() {
        RouteProgress progress=new RouteProgress();
        for(int i=0;i<2;i++) {assertTrue(progress.advance(new BlockPos(20,90,0)));assertTrue(progress.advance(new BlockPos(40,90,0)));}
        assertFalse(progress.advance(new BlockPos(20,90,0)));
    }
    @Test public void distantGoalsAreBoundedWithoutCoordinateOverflow() {
        BlockPos start=new BlockPos(-200,90,0);
        assertTrue(RouteProgress.validGoal(start,new BlockPos(800,20,1000)));
        assertTrue(RouteProgress.validGoal(start,new BlockPos(-200,1,0)));
        assertFalse(RouteProgress.validGoal(start,new BlockPos(5000,90,0)));
        assertFalse(RouteProgress.validGoal(new BlockPos(Integer.MIN_VALUE,90,0),new BlockPos(Integer.MAX_VALUE,90,0)));
    }
}
