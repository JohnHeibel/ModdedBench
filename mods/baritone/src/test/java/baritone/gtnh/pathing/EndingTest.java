// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 ModdedBench contributors
package baritone.gtnh.pathing;

import org.junit.Test;
import static org.junit.Assert.*;

public class EndingTest {
    @Test public void aJobWithSomethingToPutBackClosesFirstAndReportsItsFirstEnding() {
        Ending e=new Ending();
        assertTrue(e.defer("paused","mismatch",()->true));assertTrue(e.open());
        assertFalse("the closing is done: the end",e.defer("paused",Ending.DONE,()->{throw new AssertionError("asked once");}));
        assertEquals("paused",e.terminal("x"));assertEquals("mismatch",e.reason("x"));assertEquals("",e.cut());
    }
    @Test public void whatCutsTheClosingShortIsSaidAndTheFirstEndingStays() {
        Ending e=new Ending();
        assertTrue(e.defer("succeeded","schematic_verified",()->true));
        assertFalse(e.defer("cancelled","superseded",()->true));
        assertEquals("succeeded",e.terminal("cancelled"));assertEquals("schematic_verified",e.reason("superseded"));assertEquals("superseded",e.cut());
    }
    @Test public void aJobWithNothingToPutBackOrNoControlsEndsAtOnce() {
        Ending e=new Ending();
        assertFalse(e.defer("cancelled","interrupted",()->false));assertFalse(e.open());
        assertEquals("cancelled",e.terminal("cancelled"));assertEquals("interrupted",e.reason("interrupted"));
    }
}
