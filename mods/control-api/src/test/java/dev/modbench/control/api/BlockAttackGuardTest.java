// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 Modbench contributors
package dev.modbench.control.api;

import org.junit.Test;
import static org.junit.Assert.*;

public class BlockAttackGuardTest {
    @Test public void removalCannotContinueIntoIdenticalMachineBehindTarget() {
        Object machines=new Object(),air=new Object();
        var guard=new BlockAttackGuard(-139,75,362,machines,1);
        assertTrue(guard.permits(-139,75,362,machines,1));
        assertFalse(guard.permits(-138,75,362,machines,1));
        assertFalse(guard.permits(-139,75,362,air,0));
        assertFalse(guard.unchanged(air,0));
    }
    @Test public void replacementOrMetadataChangeEndsAttack() {
        Object initial=new Object();var guard=new BlockAttackGuard(0,70,0,initial,2);
        assertFalse(guard.unchanged(new Object(),2));assertFalse(guard.unchanged(initial,3));
        assertTrue(guard.unchanged(initial,2));
    }
}
