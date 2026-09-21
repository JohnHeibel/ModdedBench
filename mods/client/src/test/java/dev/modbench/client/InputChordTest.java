// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 ModdedBench contributors
package dev.modbench.client;

import java.util.Set;
import org.junit.Test;
import static org.junit.Assert.*;

public class InputChordTest {
    @Test public void oneTickSneakClickWaitsForPoseWithoutLosingTheClick() {
        InputChord chord=new InputChord(Set.of(42,-100),1,42,-100,-99);
        assertEquals(Set.of(42),chord.keys());
        assertFalse(chord.endTick());
        assertEquals(Set.of(42),chord.keys());
        assertFalse(chord.endTick());
        assertEquals(Set.of(42,-100),chord.keys());
        assertTrue(chord.endTick());
    }

    @Test public void useAndAttackShareOnePreludeAndKeepMovementHeld() {
        InputChord chord=new InputChord(Set.of(42,-100,-99,17),3,42,-100,-99);
        assertEquals(Set.of(42,17),chord.keys());
        assertFalse(chord.endTick()); assertFalse(chord.endTick());
        assertEquals(Set.of(42,-100,-99,17),chord.keys());
        assertFalse(chord.endTick()); assertFalse(chord.endTick());
        assertTrue(chord.endTick());
    }

    @Test public void ordinaryInputsHaveNoPrelude() {
        for(Set<Integer> keys:Set.of(Set.of(-100),Set.of(42),Set.of(17,57))) {
            InputChord chord=new InputChord(keys,1,42,-100,-99);
            assertEquals(keys,chord.keys());
            assertTrue(chord.endTick());
        }
    }
}
