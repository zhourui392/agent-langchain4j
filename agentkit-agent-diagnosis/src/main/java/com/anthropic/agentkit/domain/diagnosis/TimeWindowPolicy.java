package com.anthropic.agentkit.domain.diagnosis;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Host-selected constraints for resolving and validating diagnosis time windows.
 *
 * @author alex
 */
public record TimeWindowPolicy(Duration maximumDuration, Duration futureClockSkew) {

    public TimeWindowPolicy {
        maximumDuration = positive(maximumDuration, "maximumDuration");
        futureClockSkew = Objects.requireNonNull(futureClockSkew, "futureClockSkew");
        if (futureClockSkew.isNegative()) {
            throw new IllegalArgumentException("futureClockSkew must not be negative");
        }
    }

    public static TimeWindowPolicy withMaximum(Duration maximumDuration) {
        return new TimeWindowPolicy(maximumDuration, Duration.ZERO);
    }

    public static TimeWindowPolicy defaults() {
        return withMaximum(Duration.ofHours(24));
    }

    public Optional<String> violation(TimeWindow window, Instant now) {
        Objects.requireNonNull(window, "window");
        Objects.requireNonNull(now, "now");
        if (!window.isKnown()) {
            return Optional.of("TIME_WINDOW_UNRESOLVED");
        }
        if (window.duration().compareTo(maximumDuration) > 0) {
            return Optional.of("TIME_WINDOW_TOO_LARGE");
        }
        if (window.endExclusive().isAfter(now.plus(futureClockSkew))) {
            return Optional.of("TIME_WINDOW_IN_FUTURE");
        }
        return Optional.empty();
    }

    private static Duration positive(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }
}
