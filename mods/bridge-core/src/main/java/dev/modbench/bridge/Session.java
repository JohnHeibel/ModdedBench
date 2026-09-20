// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 Modbench contributors
package dev.modbench.bridge;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class Session {
    public final String id = UUID.randomUUID().toString();
    public final Map<String, Request> pending = new ConcurrentHashMap<>();
    public volatile boolean connected = true;
    public volatile boolean authenticated;

    public void disconnect() {
        connected = false;
        for (Request request : pending.values()) request.fail("disconnected", "bridge session closed");
        pending.clear();
    }
}
