// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 Modbench contributors
package dev.modbench.bridge;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.google.gson.JsonObject;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

/** Concurrency contract tests for the admission barrier used by background jobs. */
public class AsyncPauseTest {
    private static final long TIMEOUT_SECONDS = 3L;

    @Test
    public void activeWorkDelaysReadinessAndLateWorkWaitsUntilResume() throws Exception {
        AsyncPause gate = new AsyncPause();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch active = new CountDownLatch(1);
            CountDownLatch releaseActive = new CountDownLatch(1);
            Future<?> first = executor.submit(() -> gate.run(() -> {
                active.countDown();
                await(releaseActive);
            }));
            assertTrue(active.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));

            gate.begin();
            assertFalse(gate.ready());
            AtomicInteger lateRuns = new AtomicInteger();
            Future<?> late = executor.submit(() -> gate.run(lateRuns::incrementAndGet));
            awaitWaiting(gate, 1);
            assertEquals(0, lateRuns.get());

            releaseActive.countDown();
            first.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            awaitReady(gate);
            assertEquals(0, lateRuns.get());

            gate.resume();
            late.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            assertEquals(1, lateRuns.get());
            assertFalse(gate.ready());
            assertEquals(2L, status(gate).get("completed").getAsLong());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    public void failedWorkStillLeavesTheGateReady() throws Exception {
        AsyncPause gate = new AsyncPause();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> failed = executor.submit(() -> gate.run(() -> { throw new IllegalStateException("expected"); }));
            try {
                failed.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                throw new AssertionError("work failure was swallowed");
            } catch(ExecutionException expected) {
                assertTrue(expected.getCause() instanceof IllegalStateException);
            }
            gate.begin();
            assertTrue(gate.ready());
            assertEquals(1L, status(gate).get("completed").getAsLong());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    public void interruptedWaitingWorkLeavesNoWaitingOrActiveAdmission() throws Exception {
        AsyncPause gate = new AsyncPause();
        gate.begin();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> waiting = executor.submit(() -> gate.run(() -> { throw new AssertionError("must remain blocked"); }));
            awaitWaiting(gate, 1);
            waiting.cancel(true);
            try {
                waiting.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                throw new AssertionError("interrupted wait completed normally");
            } catch(java.util.concurrent.CancellationException expected) {
                // Cancellation is expected; the gate's finally block must still balance waiting.
            }
            awaitWaiting(gate, 0);
            assertTrue(gate.ready());
            assertEquals(0L, status(gate).get("completed").getAsLong());
        } finally {
            executor.shutdownNow();
        }
    }

    private static void awaitReady(AsyncPause gate) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);
        while(!gate.ready() && System.nanoTime() < deadline) Thread.sleep(5L);
        assertTrue("gate never became ready", gate.ready());
    }

    private static void awaitWaiting(AsyncPause gate, int expected) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);
        while(status(gate).get("waiting").getAsInt() != expected && System.nanoTime() < deadline) Thread.sleep(5L);
        assertEquals(expected, status(gate).get("waiting").getAsInt());
    }

    private static JsonObject status(AsyncPause gate) { return (JsonObject)gate.status(); }

    private static void await(CountDownLatch latch) {
        try {
            if(!latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) throw new AssertionError("test work did not release");
        } catch(InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }
}
