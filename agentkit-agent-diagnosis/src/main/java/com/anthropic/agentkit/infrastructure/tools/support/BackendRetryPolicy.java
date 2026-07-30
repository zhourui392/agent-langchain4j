package com.anthropic.agentkit.infrastructure.tools.support;

import java.time.Duration;
import java.util.Objects;

/**
 * Hard bounds for retrying one idempotent diagnosis backend query.
 *
 * @author alex
 * @since 2026-07-30
 */
public record BackendRetryPolicy(int maxRetries, Duration maxElapsed) {

    public BackendRetryPolicy {
        maxElapsed = Objects.requireNonNull(maxElapsed, "maxElapsed");
        if (maxRetries < 0 || maxRetries > 1
                || maxElapsed.isNegative() || maxElapsed.isZero()) {
            throw new IllegalArgumentException(
                    "backend retry policy permits zero or one retry within a positive deadline");
        }
    }

    public static BackendRetryPolicy noRetries() {
        return new BackendRetryPolicy(0, Duration.ofSeconds(1));
    }

    public static BackendRetryPolicy oneRetry(Duration maxElapsed) {
        return new BackendRetryPolicy(1, maxElapsed);
    }
}
