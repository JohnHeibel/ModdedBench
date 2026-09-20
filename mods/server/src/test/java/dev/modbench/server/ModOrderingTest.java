// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 Modbench contributors
package dev.modbench.server;

import static org.junit.Assert.assertTrue;

import cpw.mods.fml.common.Mod;
import org.junit.Test;

/** FML delivers FMLServerStoppingEvent in sorted order; the barrier must be resumed before GregTech waits on its executor. */
public class ModOrderingTest {
    @Test public void stoppingHandlerRunsBeforeGregTechs() {
        String dependencies=ModbenchServer.class.getAnnotation(Mod.class).dependencies();
        assertTrue(dependencies,java.util.Arrays.asList(dependencies.split(";")).contains("before:gregtech"));
    }
}
