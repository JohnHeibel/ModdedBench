// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 Modbench contributors
// Derived from Baritone (https://github.com/cabaletta/baritone), LGPL-3.0-or-later.
package baritone.gtnh.pathing;

import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;
import static baritone.gtnh.pathing.WorkSpec.*;

public class DeferredClearanceTest {
    private Cell cell(int x,boolean clear){return new Cell(new BlockPos(x,64,0),clear?"minecraft:air":"minecraft:stone",0,clear,Map.of(),Map.of(),Map.of(),Map.of());}
    @Test public void occupiedAndUnknownClearanceRemainInConstruction(){
        var solid=cell(0,false);var air=cell(1,true);var obstruction=cell(2,true);var unknown=cell(3,true);
        var cells=List.of(solid,air,obstruction,unknown);
        var deferred=DeferredClearance.capture(cells,true,p->p.equals(air.pos()));
        var desired=new HashMap<BlockPos,Cell>();cells.forEach(c->desired.put(c.pos(),c));
        var building=DeferredClearance.schematic(desired,deferred,false);
        assertEquals(Set.of(solid.pos(),obstruction.pos(),unknown.pos()),building.keySet());
        // A saved snapshot remains deferred after supports occupy the original air.
        assertEquals(desired,DeferredClearance.schematic(desired,Set.copyOf(deferred),true));
        assertEquals(4,desired.size());
    }
    @Test public void clearingOnlyAndNoPlacementDoNotInventAnExtraPhase(){
        assertTrue(DeferredClearance.capture(List.of(cell(0,true)),true,p->true).isEmpty());
        assertTrue(DeferredClearance.capture(List.of(cell(0,false),cell(1,true)),false,p->true).isEmpty());
    }
    @Test public void resumeDoesNotRequireAnExitAfterSupportsWereAlreadyRemoved(){
        var supports=Set.of(new BlockPos(1,64,1),new BlockPos(1,65,1));
        assertTrue(DeferredClearance.needsEgress(supports,p->p.y()==64));
        assertFalse(DeferredClearance.needsEgress(supports,p->true));
        assertFalse(DeferredClearance.needsEgress(Set.of(),p->false));
    }
}
