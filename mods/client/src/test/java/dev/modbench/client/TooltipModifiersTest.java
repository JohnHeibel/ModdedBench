// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 ModdedBench contributors
package dev.modbench.client;

import dev.modbench.api.UiInput;
import dev.modbench.bridge.Json;
import org.junit.Test;
import static org.junit.Assert.*;

public class TooltipModifiersTest {
    @Test public void requestedModifiersAreScopedAndOtherInputIsPreserved() {
        UiInput.clear();UiInput.modifier(29,true);UiInput.modifier(30,true);
        assertEquals("observed",TooltipModifiers.read(Json.object("shift",true,"ctrl",false),()->{
            assertTrue(UiInput.keyDown(42));assertFalse(UiInput.keyDown(29));assertTrue(UiInput.keyDown(30));
            return "observed";
        }));
        assertFalse(UiInput.keyDown(42));assertTrue(UiInput.keyDown(29));assertTrue(UiInput.keyDown(30));
        UiInput.clear();
    }
    @Test public void tooltipFailureRestoresModifiers() {
        UiInput.clear();UiInput.modifier(56,true);
        try {
            TooltipModifiers.read(Json.object("shift",true,"alt",false),()->{throw new IllegalStateException("tooltip failed");});
            fail("expected tooltip failure");
        } catch(IllegalStateException expected) {assertEquals("tooltip failed",expected.getMessage());}
        assertFalse(UiInput.keyDown(42));assertTrue(UiInput.keyDown(56));UiInput.clear();
    }
}
