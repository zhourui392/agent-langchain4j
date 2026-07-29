package com.anthropic.agentkit.domain.agent;

import java.time.Duration;
import java.util.Objects;

/** Configurable wall-clock limits inherited by tools and child agents. */
public record AgentRunLimits(
        RunDeadline deadline,
        Duration providerTimeout,
        Duration toolTimeout) {

    public static final Duration DEFAULT_PROVIDER_TIMEOUT = Duration.ofSeconds(90);
    public static final Duration DEFAULT_TOOL_TIMEOUT = Duration.ofSeconds(30);

    public AgentRunLimits {
        Objects.requireNonNull(deadline, "deadline");
        requirePositive(providerTimeout, "providerTimeout");
        requirePositive(toolTimeout, "toolTimeout");
    }

    public static AgentRunLimits defaults() {
        return new AgentRunLimits(
                RunDeadline.unlimited(), DEFAULT_PROVIDER_TIMEOUT, DEFAULT_TOOL_TIMEOUT);
    }

    public Duration providerWait() {
        return deadline.cap(providerTimeout);
    }

    public Duration toolWait() {
        return deadline.cap(toolTimeout);
    }

    public AgentRunLimits narrowedBy(AgentRunLimits requested) {
        Objects.requireNonNull(requested, "requested");
        return new AgentRunLimits(
                deadline.narrowedBy(requested.deadline),
                minimum(providerTimeout, requested.providerTimeout),
                minimum(toolTimeout, requested.toolTimeout));
    }

    private static Duration minimum(Duration first, Duration second) {
        return first.compareTo(second) <= 0 ? first : second;
    }

    private static void requirePositive(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isNegative() || value.isZero()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }
}
