package com.anthropic.agentkit.domain.agent;

import java.time.Duration;
import java.util.Objects;

/** Monotonic process-local deadline used to bound every operation in one run. */
public final class RunDeadline {

    private final long startedAtNanos;
    private final long timeoutNanos;
    private final boolean unlimited;

    private RunDeadline(long startedAtNanos, long timeoutNanos, boolean unlimited) {
        this.startedAtNanos = startedAtNanos;
        this.timeoutNanos = timeoutNanos;
        this.unlimited = unlimited;
    }

    public static RunDeadline after(Duration duration) {
        Objects.requireNonNull(duration, "duration");
        if (duration.isNegative()) {
            throw new IllegalArgumentException("deadline duration must not be negative");
        }
        return new RunDeadline(System.nanoTime(), duration.toNanos(), false);
    }

    public static RunDeadline unlimited() {
        return new RunDeadline(0, Long.MAX_VALUE, true);
    }

    public Duration remaining() {
        if (unlimited) {
            return Duration.ofNanos(Long.MAX_VALUE);
        }
        long elapsed = Math.max(0, System.nanoTime() - startedAtNanos);
        return Duration.ofNanos(Math.max(0, timeoutNanos - elapsed));
    }

    public boolean isExpired() {
        return !unlimited && remaining().isZero();
    }

    public Duration cap(Duration operationTimeout) {
        Objects.requireNonNull(operationTimeout, "operationTimeout");
        Duration remaining = remaining();
        return remaining.compareTo(operationTimeout) < 0 ? remaining : operationTimeout;
    }

    public RunDeadline narrowedBy(RunDeadline other) {
        Objects.requireNonNull(other, "other");
        return remaining().compareTo(other.remaining()) <= 0 ? this : other;
    }
}
