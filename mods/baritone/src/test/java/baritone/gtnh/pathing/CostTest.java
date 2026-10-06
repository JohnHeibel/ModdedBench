// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 ModdedBench contributors
// Derived from Baritone (https://github.com/cabaletta/baritone), LGPL-3.0-or-later.
package baritone.gtnh.pathing;

import java.util.Map;
import org.junit.Test;
import static org.junit.Assert.*;

public class CostTest {
    /** A job of these ticks, in milliseconds each. */
    private static Map<String,Object> job(double... tickMs) {
        Cost.reset();
        for(double ms:tickMs){Cost.add((long)(ms*1e6));Cost.endTick();}
        return Cost.status();
    }
    private static double[] ticks(int count,double each,double... first) {
        double[] out=new double[count];java.util.Arrays.fill(out,each);System.arraycopy(first,0,out,0,first.length);return out;
    }

    @Test public void measuredJobsSayNothing() {
        // The worst receipts on record: a hall build (max 42.9 ms, mean 1.24 over 1731 ticks), the resumed build with
        // one 98.3 ms tick in 548, a walk at 2.6 ms a tick, and two short walks whose few ticks were mostly their first.
        assertFalse(job(ticks(1731,1.2,42.9)).containsKey("overBudget"));
        assertFalse(job(ticks(548,0.87,98.3)).containsKey("overBudget"));
        assertFalse(job(ticks(400,2.6,20)).containsKey("overBudget"));
        assertFalse(job(43.4,3,2.7).containsKey("overBudget"));
        assertFalse(job(ticks(11,3.8,20.6)).containsKey("overBudget"));
        assertFalse(job().containsKey("overBudget"));
    }

    @Test public void oneTickOverItsLimitIsNamedInMilliseconds() {
        assertEquals(Map.of("tickMsMax",312.4),job(ticks(200,1,312.4)).get("overBudget"));
        assertFalse(job(ticks(200,1,Cost.TICK_LIMIT_MS)).containsKey("overBudget"));
    }

    @Test public void aSweepEveryFifthTickIsNamedByItsMean() {
        // The bug this was built after: 35 ms every fifth tick, no single tick near the one-tick limit.
        double[] run=ticks(200,1);for(int i=0;i<run.length;i+=5)run[i]=35;
        assertEquals(Map.of("tickMsMean",7.8),job(run).get("overBudget"));
    }

    @Test public void theMeanWaitsForEnoughTicksAndBothAreNamedTogether() {
        assertFalse(job(ticks((int)Cost.MEAN_TICKS-1,60)).containsKey("overBudget"));
        assertEquals(Map.of("tickMsMean",60.0),job(ticks((int)Cost.MEAN_TICKS,60)).get("overBudget"));
        assertEquals(Map.of("tickMsMax",150.0,"tickMsMean",62.3),job(ticks(40,60,150)).get("overBudget"));
    }

    @Test public void theOtherFiguresAreAsTheyWere() {
        Map<String,Object> cost=job(ticks(40,60,150));
        assertEquals(150_000_000L,cost.get("tickNsMax"));assertEquals(62_250_000L,cost.get("tickNsMean"));assertEquals(40L,cost.get("ticks"));
        assertEquals(java.util.List.of("tickNsMax","tickNsMean","ticks","pausedFrames","pausedNsMax","pausedNsMean","searches","searchMsLast","searchMsMax","overBudget"),java.util.List.copyOf(cost.keySet()));
    }
}
