// SPDX-License-Identifier: MIT
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
    /** Run of 2026-10-04: a chest's top (0.875 high, 1/16 in from each side) from 2.6 blocks away and 0.75 above it. A look
     *  toward the middle of the cell's top lands on the far edge of the chest's, and the click's own look went past it. */
    @Test public void aLowLookAtAChestAimsWhereItsTopGoesOnToEverySide() {
        double[] eye={2.6672,1.62,-.4591},lo={.0625,0,.0625},hi={.9375,.875,.9375},centre={.5,.875,.5};
        assertEquals(lo[0],top(eye,FaceSamples.points(1,centre).get(0),lo,hi)[0],.002);   // what was aimed at
        double[] aim=FaceSamples.aim(1,centre,h->top(eye,h,lo,hi));
        assertEquals(hi[1],aim[1],0);
        for(int i:new int[]{0,2})assertTrue(aim[i]-lo[i]>=FaceSamples.MARGIN&&hi[i]-aim[i]>=FaceSamples.MARGIN);
        double[] left={.49,0,.0625},right={.51,.875,.9375};                              // narrower than the margin: still clicked
        assertArrayEquals(centre,FaceSamples.aim(1,centre,h->top(eye,h,left,right)),1e-9);
        assertNull(FaceSamples.aim(1,centre,h->null));
    }
    /** Where the look from an eye above a box toward h lands on the box's top, or null. */
    private static double[] top(double[] eye,double[] h,double[] lo,double[] hi) {
        double t=(hi[1]-eye[1])/(h[1]-eye[1]);
        double[] at={eye[0]+t*(h[0]-eye[0]),hi[1],eye[2]+t*(h[2]-eye[2])};
        return t>0&&at[0]>=lo[0]&&at[0]<=hi[0]&&at[2]>=lo[2]&&at[2]<=hi[2]?at:null;
    }
}
