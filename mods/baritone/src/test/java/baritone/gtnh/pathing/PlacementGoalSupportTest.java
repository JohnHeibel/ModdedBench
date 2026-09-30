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
        assertEquals(List.of(onFloor),List.of(low,onFloor).stream().filter(p->PlacementGoalSupport.egress(target,p,false)).toList());
        assertFalse(PlacementGoalSupport.egress(new BlockPos(0,66,1),low,false));
    }
    @Test public void coveredUpwardPlacementAndScanLimitsRemainAvailable() {
        BlockPos target=new BlockPos(0,66,0);
        assertTrue(PlacementGoalSupport.egress(target,new BlockPos(1,65,0),true));
        assertTrue(PlacementGoalSupport.egress(target,new BlockPos(1,71,0),false));
        assertFalse(PlacementGoalSupport.egress(target,new BlockPos(1,72,0),false));
        assertFalse(PlacementGoalSupport.egress(target,new BlockPos(1,64,0),true));
    }
}
