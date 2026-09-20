// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 Modbench contributors
package dev.modbench.client;

import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import dev.modbench.bridge.BridgeTransport;
import net.minecraft.client.Minecraft;

@Mod(modid = "modbenchclient", name = "Modbench Client", version = "0.1.0", dependencies = "required-after:modbenchcore", acceptableRemoteVersions = "*")
public final class ModbenchClient {
    private ClientRuntime runtime;
    private BridgeTransport transport;

    @Mod.EventHandler public void init(FMLInitializationEvent event) throws Exception {
        if (!event.getSide().isClient()) return;
        runtime = new ClientRuntime();
        dev.modbench.bridge.ClockHooks.client = runtime.clock;
        transport = new BridgeTransport(runtime);
        transport.start(47223);
        Runtime.getRuntime().addShutdownHook(new Thread(transport::close, "modbench-client-close"));
    }


}
