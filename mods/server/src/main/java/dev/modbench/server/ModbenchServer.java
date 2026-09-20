// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 Modbench contributors
package dev.modbench.server;

import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.event.FMLServerStartedEvent;
import cpw.mods.fml.common.event.FMLServerStoppingEvent;
import dev.modbench.bridge.BridgeTransport;
import net.minecraft.server.MinecraftServer;

/**
 * FML delivers FMLServerStoppingEvent in load order, and GregTech's stopping handler waits (60 s + 60 s) for
 * every posted machine-update task. Sorting before gregtech makes {@link #stopping} resume the AsyncPause
 * barrier first, so tasks blocked by a pause finish instead of stalling the shutdown.
 */
@Mod(modid = "modbenchserver", name = "Modbench Server", version = "0.1.0", acceptableRemoteVersions = "*",
    dependencies = "before:gregtech")
public final class ModbenchServer {
    private ServerRuntime runtime;
    private BridgeTransport transport;

    @Mod.EventHandler public void started(FMLServerStartedEvent event) throws Exception {
        MinecraftServer server = MinecraftServer.getServer();
        if (!server.isDedicatedServer()) return;
        runtime = new ServerRuntime(server);
        dev.modbench.bridge.ClockHooks.server = runtime.clock;
        transport = new BridgeTransport(runtime);
        transport.start(47224);
    }



    @Mod.EventHandler public void stopping(FMLServerStoppingEvent event) {
        dev.modbench.bridge.AsyncPause.GREGTECH.resume();
        if (runtime != null) runtime.close();
        dev.modbench.bridge.ClockHooks.server = null;
        if (transport != null) transport.close();
    }
}
