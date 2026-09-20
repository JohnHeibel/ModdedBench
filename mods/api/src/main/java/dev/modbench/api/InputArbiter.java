// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 Modbench contributors
package dev.modbench.api;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Gives one caller at a time ownership of synthetic player input.  This class
 * deliberately has no Minecraft dependency; the host supplies the effects.
 */
public final class InputArbiter {
    public interface Sink {
        void applyKeys(Set<Integer> keys);
        void applyLook(float yaw, float pitch);
    }

    public interface Lease extends AutoCloseable {
        void setKeys(Set<Integer> keys);
        void look(float yaw, float pitch);
        /** Retain key ownership while returning camera rotation to the client. */
        void clearLook();
        boolean isActive();
        default boolean overrideProtection() {return false;}
        default boolean automatedEdits() {return false;}
        @Override void close();
    }

    /** Snapshot of the current owner. label is null while the arbiter is idle. */
    public static final class Current {
        private final String label;
        private final long operationId;
        private final boolean active;
        private final boolean overrideProtection,automatedEdits;

        private Current(String label, boolean active, boolean overrideProtection, long operationId, boolean automatedEdits) {
            this.automatedEdits=automatedEdits;
            this.operationId=operationId;
            this.label = label;
            this.active = active;
            this.overrideProtection=overrideProtection;
        }

        public String label() { return label; }
        public long operationId() {return operationId;}
        public boolean active() { return active; }
        public boolean overrideProtection() {return overrideProtection;}
        public boolean automatedEdits() {return automatedEdits;}
    }

    private final Sink sink;
    private LeaseImpl owner;
    private final java.util.concurrent.atomic.AtomicLong sequence=new java.util.concurrent.atomic.AtomicLong();
    private Set<Integer> keys = Collections.emptySet();
    private boolean hasLook;
    private float yaw;
    private float pitch;

    public InputArbiter(Sink sink) {
        this.sink = Objects.requireNonNull(sink, "sink");
    }

    /** Acquires ownership, revoking and releasing the previous owner first. */
    public Lease acquire(String label, Consumer<String> onRevoked) {
        return acquire(label,onRevoked,false);
    }

    /** Explicit per-operation override, never inherited by a new input owner. */
    public Lease acquire(String label,Consumer<String> onRevoked,boolean overrideProtection) {
        return acquire(label,onRevoked,overrideProtection,false);
    }
    /** Automatic terrain work obeys default protected regions; targeted work obeys strict regions. */
    public Lease acquire(String label,Consumer<String> onRevoked,boolean overrideProtection,boolean automatedEdits) {
        Objects.requireNonNull(label, "label");
        Objects.requireNonNull(onRevoked, "onRevoked");
        if (label.trim().isEmpty()) throw new IllegalArgumentException("label must not be blank");

        LeaseImpl previous;
        LeaseImpl next = new LeaseImpl(label, onRevoked, overrideProtection,automatedEdits);
        synchronized (this) {
            previous = owner;
            if (previous != null) previous.active = false;
            owner = next;
            keys = Collections.emptySet();
            hasLook = false;
        }
        // Release before notifying.  A reentrant callback may safely take ownership.
        applyKeysOrRevoke(next, Collections.<Integer>emptySet());
        if (previous != null) notifyRevoked(previous, "preempted");
        return next;
    }

    /** Revokes the current owner and releases all synthetic keys. */
    public void revoke(String reason) {
        Objects.requireNonNull(reason, "reason");
        LeaseImpl previous;
        synchronized (this) {
            previous = owner;
            if (previous == null) return;
            previous.active = false;
            owner = null;
            keys = Collections.emptySet();
            hasLook = false;
        }
        applyKeysBestEffort(Collections.<Integer>emptySet());
        notifyRevoked(previous, reason);
    }

    public synchronized Current current() {
        return new Current(owner == null ? null : owner.label, owner != null && owner.active, owner != null && owner.active && owner.overrideProtection, owner==null?0:owner.operationId, owner!=null&&owner.active&&owner.automatedEdits);
    }

    /** Reapplies the active state after the host's normal input processing. */
    public void reapply() {
        LeaseImpl active;
        Set<Integer> held;
        boolean applyLook;
        float currentYaw;
        float currentPitch;
        synchronized (this) {
            active = owner;
            held = keys;
            applyLook = hasLook;
            currentYaw = yaw;
            currentPitch = pitch;
        }
        if (active == null) return;
        applyKeysOrRevoke(active, held);
        if (applyLook && isOwner(active)) applyLookOrRevoke(active, currentYaw, currentPitch);
    }

    private boolean isOwner(LeaseImpl lease) {
        synchronized (this) { return owner == lease && lease.active; }
    }

    private void setKeys(LeaseImpl lease, Set<Integer> requested) {
        Objects.requireNonNull(requested, "keys");
        Set<Integer> copy = immutableKeys(requested);
        synchronized (this) {
            if (owner != lease || !lease.active) return;
            keys = copy;
        }
        applyKeysOrRevoke(lease, copy);
    }

    private void look(LeaseImpl lease, float requestedYaw, float requestedPitch) {
        if (!Float.isFinite(requestedYaw) || !Float.isFinite(requestedPitch)) {
            throw new IllegalArgumentException("look must be finite");
        }
        synchronized (this) {
            if (owner != lease || !lease.active) return;
            yaw = requestedYaw;
            pitch = requestedPitch;
            hasLook = true;
        }
        applyLookOrRevoke(lease, requestedYaw, requestedPitch);
    }

    private void close(LeaseImpl lease) {
        LeaseImpl removed = null;
        synchronized (this) {
            if (owner == lease && lease.active) {
                lease.active = false;
                owner = null;
                keys = Collections.emptySet();
                hasLook = false;
                removed = lease;
            }
        }
        if (removed != null) applyKeysBestEffort(Collections.<Integer>emptySet());
    }

    private void applyKeysOrRevoke(LeaseImpl expected, Set<Integer> requested) {
        if (!isOwner(expected)) return;
        try {
            sink.applyKeys(requested);
        } catch (RuntimeException failure) {
            failSink(expected, failure);
        }
    }

    private void applyLookOrRevoke(LeaseImpl expected, float requestedYaw, float requestedPitch) {
        if (!isOwner(expected)) return;
        try {
            sink.applyLook(requestedYaw, requestedPitch);
        } catch (RuntimeException failure) {
            failSink(expected, failure);
        }
    }

    private void failSink(LeaseImpl expected, RuntimeException ignored) {
        boolean removed = false;
        synchronized (this) {
            if (owner == expected && expected.active) {
                expected.active = false;
                owner = null;
                keys = Collections.emptySet();
                hasLook = false;
                removed = true;
            }
        }
        if (removed) {
            applyKeysBestEffort(Collections.<Integer>emptySet());
            notifyRevoked(expected, "sink_failed");
        }
    }

    private void applyKeysBestEffort(Set<Integer> requested) {
        try { sink.applyKeys(requested); } catch (RuntimeException ignored) { }
    }

    private void notifyRevoked(LeaseImpl lease, String reason) {
        try { lease.onRevoked.accept(reason); } catch (RuntimeException ignored) { }
    }

    private static Set<Integer> immutableKeys(Set<Integer> requested) {
        LinkedHashSet<Integer> copy = new LinkedHashSet<Integer>();
        for (Integer code : requested) {
            if (code == null) throw new IllegalArgumentException("key code must not be null");
            copy.add(code);
        }
        return Collections.unmodifiableSet(copy);
    }

    private final class LeaseImpl implements Lease {
        private final String label;
        private final long operationId=sequence.incrementAndGet();
        private final Consumer<String> onRevoked;
        private final boolean overrideProtection,automatedEdits;
        private boolean active = true;

        private LeaseImpl(String label, Consumer<String> onRevoked, boolean overrideProtection,boolean automatedEdits) {
            this.automatedEdits=automatedEdits;
            this.label = label;
            this.onRevoked = onRevoked;
            this.overrideProtection=overrideProtection;
        }

        @Override public void setKeys(Set<Integer> requested) { InputArbiter.this.setKeys(this, requested); }
        @Override public void look(float requestedYaw, float requestedPitch) { InputArbiter.this.look(this, requestedYaw, requestedPitch); }
        @Override public void clearLook() { synchronized(InputArbiter.this){if(owner==this&&active)hasLook=false;} }
        @Override public boolean isActive() { return InputArbiter.this.isOwner(this); }
        @Override public boolean overrideProtection() {return isActive() && overrideProtection;}
        @Override public boolean automatedEdits() {return isActive() && automatedEdits;}
        @Override public void close() { InputArbiter.this.close(this); }
    }
}
