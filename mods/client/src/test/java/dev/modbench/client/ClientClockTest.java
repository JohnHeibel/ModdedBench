// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 ModdedBench contributors
package dev.modbench.client;

import dev.modbench.bridge.Json;
import dev.modbench.bridge.Request;
import dev.modbench.bridge.Session;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ClientClockTest {
    private static Request armed(Session session) {
        Request r=new Request(new com.google.gson.JsonPrimitive(1),"act.input",new com.google.gson.JsonObject(),session,null,envelope->{});
        r.resumed=Json.object("pausedBy","requested_pause");return r;
    }
    @Test public void aResumeIsDroppedWhenTheSessionThatAskedForItIsGone() {
        Session session=new Session();Request r=armed(session);
        assertFalse(ClientClock.dropped(r));
        session.disconnect();  // before the credit tick: there is nobody to resume for, and no session to send the resume as
        assertTrue(ClientClock.dropped(r));
    }
    @Test public void pausedRefusalCarriesTheReasonAndAGuardIsNotOneToResumeThrough() {
        assertEquals("time_paused: paused by requested_pause; resume before starting simulation actions",ClientClock.refusal("requested_pause","starting simulation actions"));
        assertEquals("time_paused: paused by step; resume before executing native GUI actions",ClientClock.refusal("step","executing native GUI actions"));
        assertEquals("time_paused: world paused by a guard (health_dropped): read mb_time status, decide, resume",ClientClock.refusal("health_dropped","starting simulation actions"));
        assertEquals("time_paused: world paused by a guard (interrupt:low): read mb_time status, decide, resume",ClientClock.refusal("interrupt:low","executing native GUI actions"));
    }
}
