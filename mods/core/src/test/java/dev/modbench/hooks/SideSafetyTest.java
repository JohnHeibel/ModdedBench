// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 Modbench contributors
package dev.modbench.hooks;

import static org.junit.Assert.assertFalse;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.Test;

/** The core jar runs on the dedicated server, where any net.minecraft.client reference in a common hook kills the packet path. */
public class SideSafetyTest {
    @Test public void hookTargetsReachedOnTheServerNeverNameClientClasses() throws Exception {
        for (String name : new String[]{"GameHooks", "ClockPackets"}) {
            try (InputStream in = SideSafetyTest.class.getResourceAsStream(name + ".class")) {
                String pool = new String(in.readAllBytes(), StandardCharsets.ISO_8859_1);
                assertFalse(name + " references net.minecraft.client", pool.contains("net/minecraft/client"));
            }
        }
    }
}
