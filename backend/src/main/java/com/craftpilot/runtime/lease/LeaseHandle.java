package com.craftpilot.runtime.lease;

import java.time.Duration;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Lease state of one run as seen by its heartbeat. Shared by the run's thread and, for supervisor runs, its worker
 * threads; all reads are lock-free so loops can check it at every step without touching the database.
 */
public final class LeaseHandle implements AutoCloseable {
    public enum State {
        /** Renewing normally. */
        ACTIVE,
        /** Another owner/epoch took over, or the run is no longer active: stop at the next checkpoint. */
        LOST,
        /** execution_deadline passed: renewal stopped, the loop's own deadline check ends the run. */
        DEADLINE,
        /** The run finished on this worker and the heartbeat was cancelled. */
        CLOSED
    }

    private final String runKey;
    private final long epoch;
    private final Duration ttl;
    private final AtomicReference<State> state = new AtomicReference<>(State.ACTIVE);
    private final Consumer<LeaseHandle> onClose;
    private volatile ScheduledFuture<?> task;
    private volatile long lastRenewedNanos = System.nanoTime();

    LeaseHandle(String runKey, long epoch, Duration ttl, Consumer<LeaseHandle> onClose) {
        this.runKey = runKey;
        this.epoch = epoch;
        this.ttl = ttl;
        this.onClose = onClose;
    }

    /** A handle that never loses its lease; for callers that do not run under the heartbeat. */
    public static LeaseHandle unmanaged(String runKey, long epoch) {
        return new LeaseHandle(runKey, epoch, Duration.ofDays(3650), handle -> { });
    }

    public State state() {
        return state.get();
    }

    /** True when this worker must stop: the lease was taken over or the run already finished here. */
    public boolean isLost() {
        State s = state.get();
        return s == State.LOST || s == State.CLOSED;
    }

    public String runKey() {
        return runKey;
    }

    public long epoch() {
        return epoch;
    }

    void attach(ScheduledFuture<?> scheduled) {
        this.task = scheduled;
    }

    /** Applies one renewal outcome; returns false when the heartbeat should stop renewing. */
    boolean apply(RenewOutcome outcome) {
        switch (outcome) {
            case RENEWED -> {
                lastRenewedNanos = System.nanoTime();
                return true;
            }
            case LOST -> {
                return !transition(State.LOST);
            }
            case DEADLINE -> {
                return !transition(State.DEADLINE);
            }
            default -> {
                return true;
            }
        }
    }

    /** A renewal attempt failed with an error (e.g. database unavailable): lost only once the TTL has run out. */
    boolean applyError() {
        if (System.nanoTime() - lastRenewedNanos >= ttl.toNanos()) {
            return !transition(State.LOST);
        }
        return true;
    }

    private boolean transition(State target) {
        return state.compareAndSet(State.ACTIVE, target) || state.get() != State.ACTIVE;
    }

    void cancelTask() {
        ScheduledFuture<?> scheduled = task;
        if (scheduled != null) {
            scheduled.cancel(false);
        }
    }

    @Override
    public void close() {
        state.set(State.CLOSED);
        cancelTask();
        onClose.accept(this);
    }
}
