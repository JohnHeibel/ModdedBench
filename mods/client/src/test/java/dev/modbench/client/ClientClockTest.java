// SPDX-License-Identifier: MIT
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
    @Test public void aStepIsExtendedOnlyAtItsOwnSettledPauseAndOnlyWhenThereIsSomeoneToStepFor() {
        assertEquals("extend",ClientClock.stepEnd(true,true,true));
        assertEquals("wait",ClientClock.stepEnd(true,false,true));    // the step's pause is still settling
        assertEquals("cancel",ClientClock.stepEnd(false,true,true));  // a guard, a hold or a requested pause stands
        assertEquals("cancel",ClientClock.stepEnd(true,true,false));  // the agent is gone, or the server refused the last one
    }
    @Test public void pausedRefusalCarriesTheReasonAndAGuardIsNotOneToResumeThrough() {
        assertEquals("time_paused: paused by requested_pause; resume before starting simulation actions",ClientClock.refusal("requested_pause","starting simulation actions").getMessage());
        assertEquals("time_paused: paused by step; resume before executing native GUI actions",ClientClock.refusal("step","executing native GUI actions").getMessage());
        assertEquals("time_paused: paused by backup_hold; resume before starting simulation actions",ClientClock.refusal("backup_hold","starting simulation actions").getMessage());
        assertEquals("time_paused: world paused by a guard (health_dropped): read mb_time status, decide, resume",ClientClock.refusal("health_dropped","starting simulation actions").getMessage());
        assertEquals("time_paused",ClientClock.refusal("step","x").code); // the reply's error code, not bad_request
        assertEquals("time_paused: world paused by a guard (interrupt:low): read mb_time status, decide, resume",ClientClock.refusal("interrupt:low","executing native GUI actions").getMessage());
    }
}
