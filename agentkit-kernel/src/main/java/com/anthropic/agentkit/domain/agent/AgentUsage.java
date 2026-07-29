package com.anthropic.agentkit.domain.agent;

/** Provider-reported token usage accumulated across one agent run. */
public record AgentUsage(long inputTokens, long outputTokens, long cacheReadInputTokens) {

    public AgentUsage {
        if (inputTokens < 0 || outputTokens < 0 || cacheReadInputTokens < 0) {
            throw new IllegalArgumentException("token usage must be non-negative");
        }
    }

    public static AgentUsage zero() {
        return new AgentUsage(0, 0, 0);
    }
}
