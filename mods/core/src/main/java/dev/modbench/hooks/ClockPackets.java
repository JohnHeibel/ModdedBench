// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 ModdedBench contributors
package dev.modbench.hooks;

import dev.modbench.bridge.ClockHooks;
import net.minecraft.network.INetHandler;
import net.minecraft.network.Packet;

/** Replaces Packet.processPacket in NetworkManager: the clock admits the packet, then game events surround it. */
public final class ClockPackets {
    public static void dispatch(Packet packet, INetHandler handler) {
        if (!ClockHooks.packet(packet, handler)) GameHooks.receive(packet, handler);
    }
    private ClockPackets() {}
}
