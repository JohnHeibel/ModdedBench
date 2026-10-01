// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 ModdedBench contributors
package dev.modbench.client;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import static org.junit.Assert.*;

public class PlanHoldTest {
    private static final long FRAME=50_000_000L;

    @Test public void aGatedFrameServicesTheProviderOnce() {
        PlanHold hold=new PlanHold();AtomicInteger served=new AtomicInteger();
        for(int i=0;i<3;i++)hold.gated(served::incrementAndGet);
        assertEquals(3,served.get());assertEquals(3,hold.status().get("gatedFrames").getAsLong());
    }

    @Test public void theFirstTickWaitsWhileTheJobPlansAndIsReleasedWithoutASecondService() {
        PlanHold hold=new PlanHold();AtomicInteger served=new AtomicInteger();int[] planningFrames={3};
        long now=1_000_000_000L;int frames=0;
        // The job plans for three frames (its search in flight): each is held, after one frame of planning.
        while(hold.hold(()->planningFrames[0]-->0,served::incrementAndGet,now)){frames++;now+=FRAME;}
        assertEquals(3,frames);assertEquals(3,served.get()); // the releasing frame leaves the service to its tick
        assertEquals(1,hold.status().get("holds").getAsLong());assertEquals(150,hold.status().get("lastHeldMs").getAsLong());
        assertEquals(0,hold.status().get("capped").getAsLong());
        // An action with no planning job is not held at all, and nothing is serviced for it here.
        assertFalse(hold.hold(()->false,served::incrementAndGet,now));assertEquals(3,served.get());
        assertEquals(1,hold.status().get("holds").getAsLong());
    }

    @Test public void aPlanThatTakesTooLongStopsHoldingAtTheCap() {
        PlanHold hold=new PlanHold();AtomicInteger served=new AtomicInteger();long start=5_000_000_000L,now=start;
        while(hold.hold(()->true,served::incrementAndGet,now))now+=FRAME;
        assertEquals(PlanHold.HOLD_CAP_NS,now-start);assertEquals(PlanHold.HOLD_CAP_NS/FRAME,served.get());
        assertEquals(1,hold.status().get("capped").getAsLong());
        // A dropped credit tick (the world resumed) does not carry its start into the next action's hold.
        assertTrue(hold.hold(()->true,()->{},now+FRAME));hold.reset();
        long later=now+60*FRAME;assertTrue(hold.hold(()->true,()->{},later));assertTrue(hold.hold(()->true,()->{},later+PlanHold.HOLD_CAP_NS-1));
    }
}
