// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 ModdedBench contributors
package dev.modbench.client;

import org.junit.Test;
import static org.junit.Assert.*;

public class FaceSamplesTest {
    @Test public void grazingCellEdgesAreNotStableAimPoints() {
        assertFalse(FaceSamples.stableHit(3,.5,1.00000000000001,.1875));
        assertFalse(FaceSamples.stableHit(5,1,-.00000000000001,.5));
        assertTrue(FaceSamples.stableHit(5,1,.5,.9));
        assertTrue(FaceSamples.stableHit(1,.5,1,.5));
        assertTrue(FaceSamples.stableHit(3,.5,-.5,.1875));
    }
    @Test public void extendedSelectionBoundsDoNotExcludeTheRequestedCell() {
        var points=FaceSamples.points(5,new double[]{1,-.5,.5});
        assertArrayEquals(new double[]{1,.5,.5},points.get(0),0);
        assertArrayEquals(new double[]{1,-.5,.5},points.get(9),0);
        assertTrue(points.stream().anyMatch(p->p[0]==1&&p[1]==.5&&p[2]==.9));
    }
    @Test public void eachFaceIncludesDirectionsAroundAnObstructedCentre() {
        for(int face=0;face<6;face++) {
            int axis=face<2?1:face<4?2:0;
            var points=FaceSamples.points(face,new double[]{.5,.5,.5});
            assertEquals(10,points.size());
            for(var point:points.subList(0,9)) {
                assertEquals(face%2,point[axis],0);
                for(int i=0;i<3;i++)if(i!=axis)assertTrue(point[i]>0&&point[i]<1);
            }
        }
    }
}
