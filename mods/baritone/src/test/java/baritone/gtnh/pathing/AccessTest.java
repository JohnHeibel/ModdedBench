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
    @Test public void refusesTileEntitiesFluidsPlanCellsKeptCellsAndUnknown() {
        assertEquals("tile_entity",Access.refusal(WORLD,p(0,63,0),Set.of(),q->false));
        assertEquals("beside_fluid",Access.refusal(WORLD,p(-8,64,-7),Set.of(),q->false));
        assertEquals("plan_cell",Access.refusal(WORLD,p(8,64,8),Set.of(p(8,64,8)),q->false));
        assertEquals("kept",Access.refusal(WORLD,p(8,64,8),Set.of(),q->q.equals(p(8,64,8))));
        assertEquals("unknown",Access.refusal(WORLD,p(20,64,0),Set.of(),q->false));
        assertEquals("empty",Access.refusal(WORLD,p(8,65,8),Set.of(),q->false));
        assertNull(Access.refusal(WORLD,p(8,64,8),Set.of(),q->false));
    }
    @Test public void cellsNearATileEntityAreLeftAlone() {
        assertEquals("near_tile_entity",Access.refusal(WORLD,p(0,62,0),Set.of(),q->false));
        assertEquals("near_tile_entity",Access.refusal(WORLD,p(4,62,4),Set.of(),q->false));
        assertNull(Access.refusal(WORLD,p(5,64,0),Set.of(),q->false));
        assertTrue(Access.nearTile(WORLD,p(2,61,1)));assertFalse(Access.nearTile(WORLD,p(5,63,0)));
    }
    @Test public void aMachineJustPastTheEdgeOfTheCopyStillKeepsItsDistance() {
        ClickSpace edge=new Spaces(-10,60,-10,10,70,10,false).floor(63).solid(9,64,0).solid(3,64,0).tileOutside(12,64,2).build();
        assertEquals("near_tile_entity",Access.refusal(edge,p(9,64,0),Set.of(),q->false));
        assertNull(Access.refusal(edge,p(3,64,0),Set.of(),q->false));
        // A machine the job itself set down (a change to the copy) counts as well.
        assertEquals("near_tile_entity",Access.refusal(edge.with(p(0,64,0),ClickSpace.Voxel.full(p(0,64,0),"mod:machine",true)),p(3,64,0),Set.of(),q->false));
    }
    @Test public void aBlockWithSomethingHangingOnItOrStandingOnItStays() {
        ClickSpace lit=new Spaces(-10,60,-10,10,70,10,false).floor(63).solid(5,64,0).attached(5,65,0).solid(-5,64,0).attached(-4,64,0).solid(0,64,5).slab(0,65,5).solid(0,64,-5).build();
        assertEquals("beside_part_block",Access.refusal(lit,p(5,64,0),Set.of(),q->false));
        assertEquals("beside_part_block",Access.refusal(lit,p(-5,64,0),Set.of(),q->false));
        assertEquals("beside_part_block",Access.refusal(lit,p(0,64,5),Set.of(),q->false));
        assertNull(Access.refusal(lit,p(0,64,-5),Set.of(),q->false));
    }
}
