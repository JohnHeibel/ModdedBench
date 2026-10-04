// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 ModdedBench contributors
package dev.modbench.client;

import org.junit.Test;
import static org.junit.Assert.assertEquals;

public class ClientClockTest {
    @Test public void pausedRefusalCarriesTheReasonAndAGuardIsNotOneToResumeThrough() {
        assertEquals("time_paused: paused by requested_pause; resume before starting simulation actions",ClientClock.refusal("requested_pause","starting simulation actions"));
        assertEquals("time_paused: paused by step; resume before executing native GUI actions",ClientClock.refusal("step","executing native GUI actions"));
        assertEquals("time_paused: world paused by a guard (health_dropped): read mb_time status, decide, resume",ClientClock.refusal("health_dropped","starting simulation actions"));
        assertEquals("time_paused: world paused by a guard (interrupt:low): read mb_time status, decide, resume",ClientClock.refusal("interrupt:low","executing native GUI actions"));
    }
}
