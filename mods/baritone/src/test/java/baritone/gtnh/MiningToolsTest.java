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
}
