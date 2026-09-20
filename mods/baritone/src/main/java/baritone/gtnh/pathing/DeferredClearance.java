// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 Modbench contributors
// Derived from Baritone (https://github.com/cabaletta/baritone), LGPL-3.0-or-later.
package baritone.gtnh.pathing;

import baritone.compat.BlockPos;
import java.util.*;
import java.util.function.Predicate;
import static baritone.gtnh.pathing.WorkSpec.*;

/** Explicit final-air cells which need no initial clearance and may hold access supports. */
public final class DeferredClearance {
    private DeferredClearance() {}
    public static Set<BlockPos> capture(List<Cell> cells,boolean mayPlace,Predicate<BlockPos> initiallyAir){
        if(!mayPlace||cells.stream().allMatch(Cell::clear))return Set.of();
        Set<BlockPos> deferred=new HashSet<>();
        for(Cell cell:cells)if(cell.clear()&&initiallyAir.test(cell.pos()))deferred.add(cell.pos());
        return Set.copyOf(deferred);
    }
    public static <T> Map<BlockPos,T> schematic(Map<BlockPos,T> desired,Set<BlockPos> deferred,boolean cleanup){
        if(cleanup||deferred.isEmpty())return Map.copyOf(desired);
        Map<BlockPos,T> building=new HashMap<>(desired);deferred.forEach(building::remove);
        return Map.copyOf(building);
    }
    public static boolean needsEgress(Set<BlockPos> deferred,Predicate<BlockPos> alreadyClear){
        return deferred.stream().anyMatch(p->!alreadyClear.test(p));
    }
}
