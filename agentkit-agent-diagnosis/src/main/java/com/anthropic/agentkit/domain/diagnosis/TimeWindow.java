package com.anthropic.agentkit.domain.diagnosis;

import java.time.Duration;
import java.time.Instant;

/**
 * Absolute half-open time interval used by plans, tools, and evidence.
 *
 * @author alex
 */
public record TimeWindow(Instant startInclusive, Instant endExclusive) {

    public TimeWindow {
        boolean bothUnknown = startInclusive == null && endExclusive == null;
        boolean bothKnown = startInclusive != null && endExclusive != null;
        if (!bothUnknown && !bothKnown) {
            throw new IllegalArgumentException("time window endpoints must both be known or unknown");
        }
        if (bothKnown && !startInclusive.isBefore(endExclusive)) {
            throw new IllegalArgumentException("startInclusive must be before endExclusive");
        }
    }

    public static TimeWindow unknown() {
        return new TimeWindow(null, null);
    }

    public boolean isKnown() {
        return startInclusive != null;
    }

    public Duration duration() {
        if (!isKnown()) {
            throw new IllegalStateException("unknown time window has no duration");
        }
        return Duration.between(startInclusive, endExclusive);
    }

    public boolean contains(TimeWindow candidate) {
        if (!isKnown() || candidate == null || !candidate.isKnown()) {
            return true;
        }
        return !candidate.startInclusive().isBefore(startInclusive)
                && !candidate.endExclusive().isAfter(endExclusive);
    }
}
