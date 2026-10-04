// SPDX-License-Identifier: LGPL-3.0-or-later
package baritone.gtnh.pathing;

import baritone.compat.BlockPos;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public class PlacementGoalSupportTest {
    @Test public void openFloorEgressDoesNotFinishUnderAnAdjacentFloorCell() {
        BlockPos target=new BlockPos(0,66,0);
        // The player under one cell also overlaps its neighbour. Both formerly
        // offered this same low stance as "egress", so no movement or click ran.
        BlockPos low=new BlockPos(0,65,1),onFloor=new BlockPos(0,67,1);
        assertEquals(List.of(onFloor),List.of(low,onFloor).stream().filter(p->PlacementGoalSupport.egress(target,p,false,5)).toList());
        assertFalse(PlacementGoalSupport.egress(new BlockPos(0,66,1),low,false,5));
    }
    @Test public void coveredUpwardPlacementAndScanLimitsRemainAvailable() {
        BlockPos target=new BlockPos(0,66,0);
        assertTrue(PlacementGoalSupport.egress(target,new BlockPos(1,65,0),true,5));
        assertTrue(PlacementGoalSupport.egress(target,new BlockPos(1,71,0),false,5));
        assertFalse(PlacementGoalSupport.egress(target,new BlockPos(1,72,0),false,5));
        assertTrue(PlacementGoalSupport.egress(target,new BlockPos(1,64,0),true,5));
    }
    @Test public void ceilingSupportAllowsReachCheckedUpwardWorkWithoutPillaring() {
        BlockPos target=new BlockPos(60,71,-80),floor=new BlockPos(59,67,-80);
        assertTrue(PlacementGoalSupport.egress(target,floor,true,5));
        assertFalse(PlacementGoalSupport.egress(target,floor,false,5));
        assertFalse(PlacementGoalSupport.egress(target,new BlockPos(59,65,-80),true,5));
        assertFalse(PlacementGoalSupport.egress(target,floor,true,3));
    }
    @Test public void upwardScanFollowsNativeReach() {
        assertEquals(5,PlacementGoalSupport.reachUp(1.54,4.5));
        assertEquals(5,PlacementGoalSupport.reachUp(1.62,8));
        assertEquals(3,PlacementGoalSupport.reachUp(1.54,3));
        assertEquals(1,PlacementGoalSupport.reachUp(1.54,.5));
    }
    @Test public void aViewOnlyFromTheExactCentreIsNotAStance() {
        // Two blocks meeting at an edge let a ray through only along the exact diagonal: the line x-z=const.
        PlacementGoalSupport.Probe seam=(x,z)->x-10.5==z-20.5;
        assertTrue(seam.at(10.5,20.5));
        assertFalse(PlacementGoalSupport.steady(10.5,20.5,seam));
        // A seam along one axis keeps the nudge along it in view; the other nudge leaves it.
        assertFalse(PlacementGoalSupport.steady(10.5,20.5,(x,z)->x==10.5));
        assertFalse(PlacementGoalSupport.steady(10.5,20.5,(x,z)->z==20.5));
        assertTrue(PlacementGoalSupport.steady(10.5,20.5,(x,z)->Math.abs(x-10.5)<.3&&Math.abs(z-20.5)<.3));
        // A stance blocked at its centre costs one probe, as before.
        int[] probes={0};
        assertFalse(PlacementGoalSupport.steady(10.5,20.5,(x,z)->{probes[0]++;return false;}));
        assertEquals(1,probes[0]);
    }
}
