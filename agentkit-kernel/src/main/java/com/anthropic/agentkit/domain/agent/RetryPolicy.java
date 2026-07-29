package com.anthropic.agentkit.domain.agent;

import com.anthropic.agentkit.domain.port.ProviderFailureException;

import java.time.Duration;
import java.util.Objects;
import java.util.function.DoubleSupplier;

/** Finite exponential backoff policy for typed transient provider failures. */
public record RetryPolicy(
        int maxAttempts,
        Duration initialBackoff,
        Duration maxBackoff,
        double jitterRatio) {

    public RetryPolicy {
        if (maxAttempts <= 0) {
            throw new IllegalArgumentException("maxAttempts must be positive");
        }
        requireNonNegative(initialBackoff, "initialBackoff");
        requireNonNegative(maxBackoff, "maxBackoff");
        if (maxBackoff.compareTo(initialBackoff) < 0) {
            throw new IllegalArgumentException("maxBackoff must not be below initialBackoff");
        }
        if (jitterRatio < 0 || jitterRatio > 1) {
            throw new IllegalArgumentException("jitterRatio must be between 0 and 1");
        }
    }

    public static RetryPolicy standard() {
        return new RetryPolicy(
                3, Duration.ofMillis(200), Duration.ofSeconds(2), 0.2);
    }

    public static RetryPolicy none() {
        return fixed(1, Duration.ZERO);
    }

    public static RetryPolicy fixed(int maxAttempts, Duration backoff) {
        return new RetryPolicy(maxAttempts, backoff, backoff, 0);
    }

    public boolean permitsRetry(int completedAttempts, Throwable failure) {
        return completedAttempts < maxAttempts
                && failure instanceof ProviderFailureException provider
                && provider.kind().retryable();
    }

    public Duration delayAfter(
            int failedAttempt, ProviderFailureException failure,
            DoubleSupplier jitterSource) {
        Objects.requireNonNull(failure, "failure");
        Objects.requireNonNull(jitterSource, "jitterSource");
        Duration policyDelay = exponentialDelay(failedAttempt);
        Duration retryAfter = failure.retryAfter().orElse(Duration.ZERO);
        Duration base = policyDelay.compareTo(retryAfter) >= 0 ? policyDelay : retryAfter;
        return applyJitter(base, jitterSource.getAsDouble());
    }

    private Duration exponentialDelay(int failedAttempt) {
        if (failedAttempt <= 0) {
            throw new IllegalArgumentException("failedAttempt must be positive");
        }
        long multiplier = 1L << Math.min(failedAttempt - 1, 30);
        try {
            Duration candidate = initialBackoff.multipliedBy(multiplier);
            return candidate.compareTo(maxBackoff) <= 0 ? candidate : maxBackoff;
        } catch (ArithmeticException overflow) {
            return maxBackoff;
        }
    }

    private Duration applyJitter(Duration delay, double sample) {
        if (delay.isZero() || jitterRatio == 0) {
            return delay;
        }
        double bounded = Math.max(0, Math.min(1, sample));
        double factor = 1 - jitterRatio + (2 * jitterRatio * bounded);
        return Duration.ofNanos(Math.max(0, Math.round(delay.toNanos() * factor)));
    }

    private static void requireNonNegative(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isNegative()) {
            throw new IllegalArgumentException(name + " must not be negative");
        }
    }
}
