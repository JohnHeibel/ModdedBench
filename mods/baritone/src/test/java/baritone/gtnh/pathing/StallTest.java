// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 ModdedBench contributors
// Derived from Baritone (https://github.com/cabaletta/baritone), LGPL-3.0-or-later.
package baritone.gtnh.pathing;

import org.junit.Test;
import static org.junit.Assert.*;

public class StallTest {
    /** Ticks until the watch trips, or -1 if it never does within `ticks`. */
    private static int run(Stall stall,int ticks,java.util.function.IntFunction<int[]> at,java.util.function.IntToLongFunction progress) {
        for(int t=0;t<ticks;t++){int[] p=at.apply(t);if(stall.tick(progress.applyAsLong(t),p[0],p[1],p[2]))return t;}
        return -1;
    }

    @Test public void standingStillWithNothingDoneStallsAtTheLimit() {
        Stall stall=new Stall(800);
        assertEquals(800,run(stall,2000,t->new int[]{5,64,5},t->0));
        assertFalse(stall.advanced());
        assertEquals("stalled_no_progress_near_5,64,5",stall.reason());
    }

    @Test public void aLongWalkNeverStalls() {
        assertEquals(-1,run(new Stall(800),20000,t->new int[]{t/5,64,0},t->0));
    }

    @Test public void pacingBetweenTwoFarPlacesStallsOnceTheGroundRepeats() {
        // Back and forth over ten blocks, a lap every 100 ticks: the old two-block rule never tripped on this.
        int tripped=run(new Stall(800),5000,t->{int phase=t%100;return new int[]{phase<50?phase/5:10-(phase-50)/5,64,0};},t->0);
        assertTrue(tripped>0&&tripped<=800+100);
    }

    @Test public void circlingALoopStallsToo() {
        int tripped=run(new Stall(400),5000,t->{double a=t*2*Math.PI/200;return new int[]{(int)Math.floor(12*Math.cos(a)),70,(int)Math.floor(12*Math.sin(a))};},t->0);
        assertTrue(tripped>0&&tripped<=400+200);
    }

    @Test public void progressResetsTheWatchAndMarksTheJobAdvanced() {
        Stall stall=new Stall(800);
        // Standing still, but the progress measure moves every 500 ticks (a slow block, a smelt): never a stall.
        assertEquals(-1,run(stall,5000,t->new int[]{0,64,0},t->t/500));
        assertTrue(stall.advanced());
        // Once it stops moving, the watch runs out from the last change.
        assertEquals(800,run(stall,2000,t->new int[]{0,64,0},t->10));
    }

    @Test public void standingOnNewGroundAfterProgressIsNotReplayedAgainstOldGround() {
        Stall stall=new Stall(100);
        run(stall,50,t->new int[]{t,64,0},t->0);             // walked 0..49
        run(stall,1,t->new int[]{0,64,0},t->1);              // progress, standing back at 0
        // Walking the same blocks again is new ground for the new watch.
        assertEquals(-1,run(stall,50,t->new int[]{t,64,0},t->1));
    }

    @Test public void zeroTurnsItOff() {
        assertEquals(-1,run(new Stall(0),100000,t->new int[]{0,64,0},t->0));
    }

    @Test public void anOnlyTickedWatchCountsNoPausedTime() {
        // A paused world skips whole ticks, so the job never calls tick: 799 ticks, a pause of any length, one more tick.
        Stall stall=new Stall(800);
        assertEquals(-1,run(stall,800,t->new int[]{0,64,0},t->0));
        assertTrue(stall.tick(0,0,64,0));
    }

    @Test public void negativeAndExtremeCoordinatesAreDistinctCells() {
        Stall stall=new Stall(3);
        assertFalse(stall.tick(0,-1,0,-1));assertFalse(stall.tick(0,1,0,1));assertFalse(stall.tick(0,-30000000,255,30000000));
        assertFalse(stall.tick(0,30000000,1,-30000000));
        assertEquals(4,stall.status().get("groundCells"));
    }
}
