// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 Modbench contributors
package dev.modbench.control.api;

import org.junit.*;
import static org.junit.Assert.*;

public class UiInputTest {
    @Before public void clear() {UiInput.clear();}
    @After public void cleanup() {UiInput.clear();}
    @Test public void mouseStateTracksEachEventAndCancellationClearsPendingRelease() {
        UiInput.postMouse(12,14,0,true,0);UiInput.postMouse(20,22,-1,false,120);UiInput.postMouse(20,22,0,false,0);
        assertTrue(UiInput.nextMouse());assertTrue(UiInput.buttonDown(0));
        assertTrue(UiInput.nextMouse());assertEquals(120,UiInput.mouse().wheel());assertTrue(UiInput.buttonDown(0));
        UiInput.clear();assertFalse(UiInput.buttonDown(0));assertFalse(UiInput.nextMouse());assertNull(UiInput.mouse());assertEquals(-1,UiInput.x());
    }
    @Test public void keyEventGettersAndPolledModifiersAgree() {
        UiInput.modifier(42,true);UiInput.postKey(30,'a',true);UiInput.postKey(30,'a',false);
        assertTrue(UiInput.nextKey());assertTrue(UiInput.keyDown(30));assertTrue(UiInput.keyDown(42));assertEquals('a',UiInput.key().character());
        assertTrue(UiInput.nextKey());assertFalse(UiInput.keyDown(30));assertTrue(UiInput.keyDown(42));
        assertFalse(UiInput.nextKey());assertNull(UiInput.key());UiInput.clear();assertFalse(UiInput.keyDown(42));
    }
    @Test public void boundedQueuesRejectOverflowAndRecover() {
        for(int i=0;i<1024;i++) UiInput.postMouse(0,0,-1,false,0);
        assertThrows(IllegalStateException.class,()->UiInput.postMouse(0,0,-1,false,0));
        UiInput.clear();assertEquals(0,UiInput.pending());UiInput.postKey(1,' ',true);assertTrue(UiInput.nextKey());
    }
}
