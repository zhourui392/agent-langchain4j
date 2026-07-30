package com.anthropic.agentkit.domain.diagnosis;

import java.util.Objects;
import java.util.Optional;

/**
 * Deterministic time expression resolution result without free-form exception parsing.
 *
 * @author alex
 */
public record TimeResolution(Optional<TimeWindow> window, String reasonCode) {

    public TimeResolution {
        window = Objects.requireNonNull(window, "window");
        reasonCode = SecretDataPolicy.sanitize(reasonCode);
        if (window.isEmpty() && reasonCode.isEmpty()) {
            throw new IllegalArgumentException("unresolved time requires a reasonCode");
        }
    }

    public static TimeResolution resolved(TimeWindow window) {
        return new TimeResolution(Optional.of(window), "");
    }

    public static TimeResolution unresolved(String reasonCode) {
        return new TimeResolution(Optional.empty(), reasonCode);
    }

    public boolean resolved() {
        return window.isPresent();
    }
}
