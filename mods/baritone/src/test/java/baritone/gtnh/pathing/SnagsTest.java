// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 ModdedBench contributors
// Derived from Baritone (https://github.com/cabaletta/baritone), LGPL-3.0-or-later.
package baritone.gtnh.pathing;

import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class SnagsTest {
    private static final Snags.Edge EDGE=new Snags.Edge(33,56,-77,34,56,-76);
    private static Map<String,Object> at(int x,int y,int z){return Map.of("kind","snagged","at",List.of(x,y,z));}

    @Test public void aSnagIsForwardAgainstACollisionWithoutMovingForEightTicks() {
        Snags snags=new Snags();
        for(int t=1;t<Snags.TICKS;t++)assertFalse(snags.tick(true,true,0));
        assertTrue(snags.tick(true,true,0));
        // It starts over after firing, and any motion, a free side or a released key breaks the run.
        assertFalse(snags.tick(true,true,0));
        for(int t=0;t<20;t++){assertFalse(snags.tick(true,true,t%5==0?.02:0));}
        for(int t=0;t<20;t++){assertFalse(snags.tick(true,t%5!=0,0));}
        for(int t=0;t<20;t++){assertFalse(snags.tick(t%5!=0,true,0));}
    }

    @Test public void anEdgeIsRetriedTwiceThenBannedForTheJob() {
        Snags snags=new Snags();
        assertTrue(snags.allows(33,56,-77,34,56,-76));
        assertEquals(Snags.Verdict.RETRY,snags.failed(EDGE,Snags.RETRIES,at(33,56,-77)));
        assertEquals(Snags.Verdict.RETRY,snags.failed(EDGE,Snags.RETRIES,at(33,56,-77)));
        assertEquals(Snags.Verdict.BAN,snags.failed(EDGE,Snags.RETRIES,at(33,56,-77)));
        assertFalse(snags.allows(33,56,-77,34,56,-76));
        // Only that direction: the way back is another edge.
        assertTrue(snags.allows(34,56,-76,33,56,-77));
        assertEquals("snagged_at_33,56,-77",snags.cause());
        assertEquals(3,((Map<?,?>)snags.status().get("last")).get("failures"));
        assertNull(snags.failure());
        snags.reset();
        assertTrue(snags.allows(33,56,-77,34,56,-76));assertNull(snags.cause());
    }

    @Test public void aRepeatedUnreachableMovementIsBannedOnItsSecondFailure() {
        Snags snags=new Snags();
        Map<String,Object> detail=Map.of("kind","unreachable_movement","at",List.of(58,16,-159));
        assertEquals(Snags.Verdict.RETRY,snags.failed(EDGE,1,detail));
        assertEquals(Snags.Verdict.BAN,snags.failed(EDGE,1,detail));
        assertEquals("unreachable_movement_at_58,16,-159",snags.cause());
    }

    @Test public void tooManyBannedEdgesEndTheJob() {
        Snags snags=new Snags();
        for(int i=0;i<=Snags.MAX_BANS;i++){assertNull(snags.failure());snags.failed(new Snags.Edge(i,64,0,i+1,64,0),0,at(i,64,0));}
        assertEquals("snagged_at_"+Snags.MAX_BANS+",64,0",snags.failure());
    }
}
