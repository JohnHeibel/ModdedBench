// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 ModdedBench contributors
package baritone.gtnh.pathing;

import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;
import static baritone.gtnh.pathing.Spaces.*;

public class AccessTest {
    private static final ClickSpace WORLD=new Spaces(-10,60,-10,10,70,10,false).floor(60).floor(61).floor(62).floor(63)
        .tile(0,63,0,"mod:machine").water(-8,64,-8).solid(-8,64,-7).solid(5,64,0).solid(8,64,8).build();
    @Test public void defaultsAndLimits() {
        Access a=Access.parse(Map.of());assertFalse(a.allowed());assertEquals(4,a.tileDistance());assertEquals(3,a.maxCells());
        assertThrows(IllegalArgumentException.class,()->Access.parse(Map.of("allow",true,"tileDistance",40)));
        assertThrows(IllegalArgumentException.class,()->Access.parse(Map.of("allowed",true)));
        assertEquals(a,Access.parse(new HashMap<>(a.json())));
    }
    @Test public void refusesTileEntitiesFluidsPlanCellsProtectionAndUnknown() {
        Access a=Access.parse(Map.of("allow",true));
        assertEquals("tile_entity",a.refusal(WORLD,p(0,63,0),Set.of(),q->false));
        assertEquals("beside_fluid",a.refusal(WORLD,p(-8,64,-7),Set.of(),q->false));
        assertEquals("plan_cell",a.refusal(WORLD,p(8,64,8),Set.of(p(8,64,8)),q->false));
        assertEquals("protected",a.refusal(WORLD,p(8,64,8),Set.of(),q->q.equals(p(8,64,8))));
        assertEquals("unknown",a.refusal(WORLD,p(20,64,0),Set.of(),q->false));
        assertEquals("empty",a.refusal(WORLD,p(8,65,8),Set.of(),q->false));
        assertNull(a.refusal(WORLD,p(8,64,8),Set.of(),q->false));
    }
    @Test public void cellsNearATileEntityNeedExplicitBoundsAndAnIdenticalRestore() {
        Access a=Access.parse(Map.of("allow",true));
        assertEquals("near_tile_entity",a.refusal(WORLD,p(0,62,0),Set.of(),q->false));
        assertEquals("near_tile_entity",a.refusal(WORLD,p(4,62,4),Set.of(),q->false));
        assertNull(a.refusal(WORLD,p(5,64,0),Set.of(),q->false));
        assertFalse(a.substituteAllowed(WORLD,p(4,62,0)));assertTrue(a.substituteAllowed(WORLD,p(8,64,8)));
        Access bounded=Access.parse(Map.of("allow",true,"bounds",List.of(Map.of("min",List.of(-1,62,-1),"max",List.of(1,62,1)))));
        assertNull(bounded.refusal(WORLD,p(0,62,0),Set.of(),q->false));
        assertEquals("tile_entity",bounded.refusal(WORLD,p(0,63,0),Set.of(),q->false));
        assertFalse(bounded.substituteAllowed(WORLD,p(0,62,0)));
        assertEquals(p(0,63,0),Access.tileWithin(WORLD,p(2,61,1),4));assertNull(Access.tileWithin(WORLD,p(2,61,1),1));
        assertNull(Access.parse(Map.of("allow",true,"tileDistance",0)).refusal(WORLD,p(0,62,0),Set.of(),q->false));
    }
}
