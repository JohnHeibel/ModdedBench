// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 Modbench contributors
package dev.modbench.clock;

import dev.modbench.bridge.ClockHooks;
import net.minecraft.network.INetHandler;
import net.minecraft.network.Packet;

public final class ClockPackets {
    public static void dispatch(Packet packet, INetHandler handler) {
        if (!ClockHooks.packet(packet, handler)) packet.processPacket(handler);
    }
    private ClockPackets() {}
}
