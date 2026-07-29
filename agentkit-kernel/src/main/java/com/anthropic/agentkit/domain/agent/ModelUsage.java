package com.anthropic.agentkit.domain.agent;

import java.util.Objects;

/** Usage and attempt count attributed to one actual provider/model identity. */
public record ModelUsage(
        ModelIdentity model,
        int attempts,
        long inputTokens,
        long outputTokens,
        long cacheReadInputTokens) {

    public ModelUsage {
        Objects.requireNonNull(model, "model");
        if (attempts < 0 || inputTokens < 0 || outputTokens < 0
                || cacheReadInputTokens < 0) {
            throw new IllegalArgumentException("model usage must be non-negative");
        }
    }
}
