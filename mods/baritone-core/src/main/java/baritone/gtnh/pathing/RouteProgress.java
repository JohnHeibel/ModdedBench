// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 Modbench contributors
// Derived from Baritone (https://github.com/cabaletta/baritone), LGPL-3.0-or-later.
package baritone.gtnh.pathing;

import java.util.HashMap;
import java.util.Map;

/** Bounds continuation without treating normal snapshot boundaries as recovery failures. */
public final class RouteProgress {
    private final Map<BlockPos,Integer> endpoints=new HashMap<>();
    private int segments;
    public boolean advance(BlockPos endpoint) {
        return ++segments<=1024 && endpoints.merge(endpoint,1,Integer::sum)<=2;
    }
    public int segments() {return segments;}
    public static boolean validGoal(BlockPos start,BlockPos goal) {
        return goal.y()>=1 && goal.y()<=254 && Math.abs((long)goal.x())<=30000000 && Math.abs((long)goal.z())<=30000000
            && Math.abs((long)goal.x()-start.x())+Math.abs((long)goal.z()-start.z())<=4096;
    }
}
