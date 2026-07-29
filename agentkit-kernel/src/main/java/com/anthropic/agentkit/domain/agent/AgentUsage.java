package com.anthropic.agentkit.domain.agent;

import java.util.List;
import java.util.Objects;

/** Provider-reported token usage accumulated across one agent run. */
public record AgentUsage(
        long inputTokens,
        long outputTokens,
        long cacheReadInputTokens,
        List<ModelUsage> modelUsage) {

    public AgentUsage(long inputTokens, long outputTokens, long cacheReadInputTokens) {
        this(inputTokens, outputTokens, cacheReadInputTokens, List.of());
    }

    public AgentUsage {
        if (inputTokens < 0 || outputTokens < 0 || cacheReadInputTokens < 0) {
            throw new IllegalArgumentException("token usage must be non-negative");
        }
        modelUsage = List.copyOf(Objects.requireNonNull(modelUsage, "modelUsage"));
    }

    public static AgentUsage zero() {
        return new AgentUsage(0, 0, 0, List.of());
    }
}
