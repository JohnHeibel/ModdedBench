// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 ModdedBench contributors
package baritone.gtnh;

import baritone.ForgePlanningTestRunner;
import baritone.gtnh.pathing.WorkSpec;
import java.util.*;
import org.junit.*;
import static org.junit.Assert.*;

@org.junit.runner.RunWith(ForgePlanningTestRunner.class)
public class MiningObservationTest {
    @Test public void afterTheFirstPassAScanTickReadsASmallSlice(){
        // A thousand cells and no selectors: nothing is asked of the world.
        var scan=new MiningObservation(null,WorkSpec.bounds(Map.of("min",List.of(0,0,0),"max",List.of(9,9,9))),List.of(),List.of());
        for(int t=0;t<1000&&scan.passes==0;t++)scan.tick();
        assertEquals(1,scan.passes);assertEquals(0,scan.cursor);
        scan.tick();
        assertEquals(1,scan.passes);assertTrue(""+scan.cursor,scan.cursor<=256);
    }
}
