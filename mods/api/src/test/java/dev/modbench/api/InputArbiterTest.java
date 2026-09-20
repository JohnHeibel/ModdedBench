// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 Modbench contributors
package dev.modbench.api;

import org.junit.Test;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import static org.junit.Assert.*;

public class InputArbiterTest {
    @Test public void releasingCameraPreservesKeysAndCannotClearAnotherOwnersCamera() {
        List<Set<Integer>> applied=new ArrayList<>();RecordingSink sink=new RecordingSink(applied);
        InputArbiter arbiter=new InputArbiter(sink);var first=arbiter.acquire("first",ignored->{});
        first.setKeys(keys(4));first.look(10,20);first.clearLook();sink.yaw=30;arbiter.reapply();
        assertEquals(30,sink.yaw,0);assertEquals(keys(4),applied.get(applied.size()-1));
        var second=arbiter.acquire("second",ignored->{});second.look(45,0);first.clearLook();sink.yaw=0;arbiter.reapply();
        assertEquals(45,sink.yaw,0);
    }
    @Test public void preemptionReleasesBeforeCallbackAndStaleLeaseCannotChangeNewOwner() {
        List<Set<Integer>> applied = new ArrayList<Set<Integer>>();
        List<String> revoked = new ArrayList<String>();
        InputArbiter arbiter = new InputArbiter(new RecordingSink(applied));
        InputArbiter.Lease first = arbiter.acquire("first", revoked::add);
        first.setKeys(keys(1, 2));
        InputArbiter.Lease second = arbiter.acquire("second", revoked::add);
        assertEquals(keys(), applied.get(applied.size() - 1));
        assertEquals(java.util.Collections.singletonList("preempted"), revoked);
        first.setKeys(keys(9));
        first.close();
        assertTrue(second.isActive());
        assertEquals("second", arbiter.current().label());
        second.setKeys(keys(3));
        assertEquals(keys(3), applied.get(applied.size() - 1));
    }

    @Test public void closeCleansUpAndReapplyRestoresKeysAndLook() {
        List<Set<Integer>> applied = new ArrayList<Set<Integer>>();
        RecordingSink sink = new RecordingSink(applied);
        InputArbiter arbiter = new InputArbiter(sink);
        InputArbiter.Lease lease = arbiter.acquire("owner", ignored -> fail("unexpected revoke"));
        lease.setKeys(keys(4));
        lease.look(10.0f, 20.0f);
        arbiter.reapply();
        assertEquals(keys(4), applied.get(applied.size() - 1));
        assertEquals(10.0f, sink.yaw, 0.0f);
        lease.close();
        assertEquals(keys(), applied.get(applied.size() - 1));
        assertFalse(lease.isActive());
    }

    @Test public void reentrantAndThrowingRevocationCannotLeaveKeysHeld() {
        List<Set<Integer>> applied = new ArrayList<Set<Integer>>();
        InputArbiter arbiter = new InputArbiter(new RecordingSink(applied));
        final InputArbiter.Lease[] replacement = new InputArbiter.Lease[1];
        InputArbiter.Lease old = arbiter.acquire("old", reason -> {
            replacement[0] = arbiter.acquire("callback", ignored -> { throw new RuntimeException(); });
            replacement[0].setKeys(keys(7));
            throw new RuntimeException("callback failure");
        });
        old.setKeys(keys(1));
        InputArbiter.Lease requested = arbiter.acquire("requested", ignored -> { });
        assertFalse(old.isActive());
        assertFalse(requested.isActive());
        assertTrue(replacement[0].isActive());
        replacement[0].close();
        assertEquals(keys(), applied.get(applied.size() - 1));
    }

    @Test public void sinkFailureRevokesAndAttemptsCleanup() {
        List<Set<Integer>> applied = new ArrayList<Set<Integer>>();
        InputArbiter arbiter = new InputArbiter(new InputArbiter.Sink() {
            private boolean fail = true;
            @Override public void applyKeys(Set<Integer> pressed) {
                applied.add(new LinkedHashSet<Integer>(pressed));
                if (fail && !pressed.isEmpty()) {
                    fail = false;
                    throw new RuntimeException("synthetic sink failure");
                }
            }
            @Override public void applyLook(float yaw, float pitch) { }
        });
        List<String> revoked = new ArrayList<String>();
        InputArbiter.Lease lease = arbiter.acquire("owner", revoked::add);
        lease.setKeys(keys(8));
        assertFalse(lease.isActive());
        assertNull(arbiter.current().label());
        assertEquals(java.util.Collections.singletonList("sink_failed"), revoked);
        assertEquals(keys(), applied.get(applied.size() - 1));
    }

    private static Set<Integer> keys(Integer... values) {
        return new LinkedHashSet<Integer>(java.util.Arrays.asList(values));
    }

    private static final class RecordingSink implements InputArbiter.Sink {
        private final List<Set<Integer>> keys;
        private float yaw;
        private float pitch;
        private RecordingSink(List<Set<Integer>> keys) { this.keys = keys; }
        @Override public void applyKeys(Set<Integer> pressed) { keys.add(new LinkedHashSet<Integer>(pressed)); }
        @Override public void applyLook(float yaw, float pitch) { this.yaw = yaw; this.pitch = pitch; }
    }
}
