// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 ModdedBench contributors
package baritone.gtnh.pathing;

import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;

public class ClickLogTest {
    /** What the next session reads back: the journal's map, through JSON's eyes. */
    private static ClickLog resumed(ClickLog log){return new ClickLog(new LinkedHashMap<>(log.results));}
    @Test public void aUseThatWasMadeIsNotMadeAgainByALaterSession() {
        ClickLog log=new ClickLog(Map.of());
        assertFalse(log.settled("use0"));
        log.record("use0",ClickLog.TAKING);assertFalse("not settled until something came of it",log.settled("use0"));assertFalse("this session's own click is not an interrupted one",log.interrupted("use0"));
        log.record("use0","used");
        ClickLog next=resumed(log);
        assertTrue(next.settled("use0"));assertFalse(next.interrupted("use0"));
    }
    @Test public void aUseCutOffBetweenThePressAndItsResultIsUnknownAndNeverClickedAgain() {
        ClickLog log=new ClickLog(Map.of());log.record("use0",ClickLog.TAKING);
        ClickLog next=resumed(log);
        assertTrue("the job stops on it: unknown_after_restart",next.interrupted("use0"));assertFalse(next.settled("use0"));
        next.record("use0","unknown");
        assertFalse(next.interrupted("use0"));assertTrue(next.settled("use0"));
        ClickLog third=resumed(next);
        assertTrue("a further resume goes past it",third.settled("use0"));assertFalse(third.interrupted("use0"));assertTrue(third.unverified("use0"));
    }
    @Test public void aUseThatOpenedAScreenCountsAsMade() {
        ClickLog log=new ClickLog(Map.of());log.record("use1",ClickLog.TAKING);log.record("use1","gui_opened");
        assertTrue(resumed(log).settled("use1"));assertTrue(log.unverified("use1"));
    }
    @Test public void aPlaceWhoseBlockIsGoneIsDroppedAndMadeAgain() {
        ClickLog log=new ClickLog(Map.of("1,64,1","verified"));
        assertTrue(log.settled("1,64,1"));log.drop("1,64,1");assertFalse(log.settled("1,64,1"));assertNull(log.of("1,64,1"));
    }
    @Test public void theReceiptCountsEveryClickOnce() {
        ClickLog log=new ClickLog(Map.of());
        for(var e:List.of("a:verified","b:verified","c:already_present","d:unverified","e:expect_failed","f:gui_opened","g:unknown","h:used","i:taking")){var kv=e.split(":");log.record(kv[0],kv[1]);}
        assertEquals(Map.of("done",8,"verified",2,"unverified",4,"alreadyPresent",1),log.counts());
    }
    @Test public void aStallDuringAClickIsNamedForThePhaseItWasIn() {
        assertEquals("no_route_from_here",ClickLog.stalled("moving"));
        assertEquals("aim_mismatch",ClickLog.stalled("aim"));
        assertEquals("access_failed",ClickLog.stalled("break"));
        assertEquals("placement_rejected",ClickLog.stalled("settle"));
        assertEquals("expect_timeout",ClickLog.stalled("expect"));
        for(String phase:List.of("capture","search","select","selecting","idle"))assertEquals(phase,"stalled",ClickLog.stalled(phase));
    }
}
