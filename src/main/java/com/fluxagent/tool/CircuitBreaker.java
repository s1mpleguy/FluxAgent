package com.fluxagent.tool;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

public class CircuitBreaker {
    private final int threshold;
    private final Duration recovery;
    private final Clock clock;
    private CircuitState state = CircuitState.CLOSED;
    private int failCount;
    private long generation;
    private Instant openedAt;

    public CircuitBreaker(int threshold, Duration recovery) {
        this(threshold, recovery, Clock.systemUTC());
    }

    public CircuitBreaker(int threshold, Duration recovery, Clock clock) {
        if (threshold < 1 || recovery == null || recovery.isNegative() || recovery.isZero()) {
            throw new IllegalArgumentException("Invalid circuit breaker configuration");
        }
        this.threshold = threshold;
        this.recovery = recovery;
        this.clock = clock;
    }

    public synchronized Permit acquire() {
        if (state == CircuitState.CLOSED) {
            return new Permit(generation, false);
        }
        if (state == CircuitState.OPEN && !clock.instant().isBefore(openedAt.plus(recovery))) {
            state = CircuitState.HALF_OPEN;
            return new Permit(generation, true);
        }
        return null;
    }

    public synchronized void recordSuccess(Permit permit) {
        if (!current(permit)) { return; }
        if (permit.probe) { generation++; }
        failCount = 0;
        state = CircuitState.CLOSED;
        openedAt = null;
    }

    public synchronized void recordFailure(Permit permit) {
        if (!current(permit)) { return; }
        failCount++;
        if (permit.probe || failCount >= threshold) {
            state = CircuitState.OPEN;
            openedAt = clock.instant();
            generation++;
        }
    }

    private boolean current(Permit permit) {
        return permit != null && permit.generation == generation
                && ((state == CircuitState.CLOSED && !permit.probe)
                    || (state == CircuitState.HALF_OPEN && permit.probe));
    }

    public synchronized CircuitState state() { return state; }

    /** Identifies the circuit generation and its single recovery probe. */
    public static final class Permit {
        private final long generation;
        private final boolean probe;

        private Permit(long generation, boolean probe) {
            this.generation = generation;
            this.probe = probe;
        }
    }
}
