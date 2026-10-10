// SPDX-License-Identifier: MIT
// Copyright (c) 2026 ModdedBench contributors
package dev.modbench.bridge;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

public class ComputerPauseTest {
    /** Stands in for an OpenComputers machine: the adapter only ever calls pause(double). */
    public static final class Machine {
        final AtomicInteger paused=new AtomicInteger();
        volatile boolean broken;
        public boolean pause(double seconds) { if(broken) throw new IllegalStateException("machine is gone");paused.incrementAndGet();return true; }
    }
    private static void settle() throws InterruptedException {
        for(int i=0;i<500 && !ComputerPause.ready();i++) Thread.sleep(10);
        assertTrue("every machine was asked",ComputerPause.ready());
    }

    @Test
    public void aMachineWhosePauseThrowsIsReportedAndNeitherSkipsTheOthersNorBlocksEveryLaterResume() throws Exception {
        Machine broken=new Machine(),sound=new Machine();broken.broken=true;
        ComputerPause.register(broken);ComputerPause.register(sound);
        ComputerPause.begin();settle();
        assertEquals("the machine after the broken one is still paused",1,sound.paused.get());
        assertEquals("java.lang.IllegalStateException: machine is gone",ComputerPause.failure());
        ComputerPause.resume(); // used to throw "computer pause has not settled" from here on, for ever
        // The next pause starts from a fresh state: the old failure is gone once the machine behaves (or is gone).
        broken.broken=false;
        ComputerPause.begin();settle();
        assertNull(ComputerPause.failure());
        assertEquals(1,broken.paused.get());assertEquals(2,sound.paused.get());
        ComputerPause.resume();
        java.lang.ref.Reference.reachabilityFence(broken);java.lang.ref.Reference.reachabilityFence(sound); // the registry holds them weakly
    }
}
