// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 ModdedBench contributors
// Derived from Baritone (https://github.com/cabaletta/baritone), LGPL-3.0-or-later.
package baritone.gtnh;
import org.junit.Test;
import static org.junit.Assert.*;

public class MiningToolsTest {
    @Test public void instantBreakStrengthIsOneTick() {
        assertEquals(1,MiningTools.breakTicks(Double.POSITIVE_INFINITY),0);
        assertEquals(1,MiningTools.breakTicks(1),0);
        assertEquals(4,MiningTools.breakTicks(.25),0);
    }
    @Test public void invalidAndUnbreakableStrengthsStayRejected() {
        for(double strength:new double[]{Double.NaN,Double.NEGATIVE_INFINITY,-1,0})
            assertEquals(Double.POSITIVE_INFINITY,MiningTools.breakTicks(strength),0);
    }
    @Test public void onePickPrefersHarvestThenSpeedThenTheHeldSlot() {
        var slow=new ReferenceToolPolicy.Answer(.1,true);var fast=new ReferenceToolPolicy.Answer(.5,false);var none=new ReferenceToolPolicy.Answer(0,true);
        var answers=new ReferenceToolPolicy.Answer[]{fast,slow,slow,none,null};boolean[] all={true,true,true,true,true};
        assertEquals(1,MiningTools.pick(answers,all,MiningTools.order(0,5)));
        assertEquals(2,MiningTools.pick(answers,all,MiningTools.order(2,5)));
        assertEquals(0,MiningTools.pick(answers,new boolean[]{true,false,false,true,true},MiningTools.order(0,5)));
        assertEquals(-1,MiningTools.pick(new ReferenceToolPolicy.Answer[]{none,null},new boolean[]{true,true},MiningTools.order(0,2)));
    }
}
