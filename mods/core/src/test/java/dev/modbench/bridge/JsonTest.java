// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 ModdedBench contributors
package dev.modbench.bridge;

import static org.junit.Assert.assertEquals;

import com.google.gson.JsonObject;
import org.junit.Test;

/** Numeric boundaries are validated before requests reach a game handler. */
public class JsonTest {
    @Test
    public void terrainEditingFlagsRequireActualBooleans() {
        JsonObject params = new JsonObject();
        assertEquals(false, Json.bool(params, "allowBreak", false));
        params.addProperty("allowBreak", true);
        assertEquals(true, Json.bool(params, "allowBreak", false));
        params.addProperty("allowBreak", "true");
        try {
            Json.bool(params, "allowBreak", false);
            throw new AssertionError("string must not enable terrain editing");
        } catch (IllegalArgumentException expected) { }
    }
    @Test
    public void integerRejectsFractionalNonFiniteAndOutOfRangeValues() {
        assertInvalidInteger(1.5);
        assertInvalidInteger(Double.NaN);
        assertInvalidInteger(11);
        JsonObject valid = new JsonObject();
        valid.addProperty("value", 10.0);
        assertEquals(10, Json.integer(valid, "value", 3, 1, 10));
    }

    @Test
    public void numberRejectsNonFiniteAndOutOfRangeValues() {
        assertInvalidNumber(Double.POSITIVE_INFINITY);
        assertInvalidNumber(-0.1);
        JsonObject valid = new JsonObject();
        valid.addProperty("value", 0.5);
        assertEquals(0.5, Json.number(valid, "value", 0, 0, 1), 0.0);
    }

    private static void assertInvalidInteger(double value) {
        JsonObject params = new JsonObject();
        params.addProperty("value", value);
        try {
            Json.integer(params, "value", 3, 1, 10);
            throw new AssertionError("expected invalid integer: " + value);
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }

    private static void assertInvalidNumber(double value) {
        JsonObject params = new JsonObject();
        params.addProperty("value", value);
        try {
            Json.number(params, "value", 0, 0, 1);
            throw new AssertionError("expected invalid number: " + value);
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }
}
