// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 Modbench contributors
// Derived from Baritone (https://github.com/cabaletta/baritone), LGPL-3.0-or-later.
package baritone.gtnh.pathing;

import baritone.compat.BlockPos;
import org.junit.Test;
import static org.junit.Assert.*;

public class CorridorTest {
    @Test public void routeCorridorIncludesElevationAndEndpointCaps() {
        Corridor corridor=new Corridor(new BlockPos(1,1,2),new BlockPos(7,7,2),1);
        assertTrue(corridor.contains(new BlockPos(4,4,2)));
        assertTrue(corridor.contains(new BlockPos(4,4,3)));
        assertFalse(corridor.contains(new BlockPos(4,1,2)));
        assertTrue(corridor.contains(new BlockPos(1,1,1)));
        assertFalse(corridor.contains(new BlockPos(0,0,2)));
    }
}
