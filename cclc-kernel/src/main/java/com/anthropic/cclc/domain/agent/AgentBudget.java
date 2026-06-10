package com.anthropic.cclc.domain.agent;

/**
 * Generic execution budget for a kernel agent run.
 *
 * @author zhourui(V33215020)
 * @since 2026-06-11
 */
public record AgentBudget(int maxTurns, int maxToolCalls, long maxInputTokens) {

    public AgentBudget {
        if (maxTurns < 0) {
            throw new IllegalArgumentException("maxTurns must be >= 0");
        }
        if (maxToolCalls < 0) {
            throw new IllegalArgumentException("maxToolCalls must be >= 0");
        }
        if (maxInputTokens < 0) {
            throw new IllegalArgumentException("maxInputTokens must be >= 0");
        }
    }

    public static AgentBudget of(int maxTurns, int maxToolCalls, long maxInputTokens) {
        return new AgentBudget(maxTurns, maxToolCalls, maxInputTokens);
    }

    public static AgentBudget unlimited() {
        return new AgentBudget(Integer.MAX_VALUE, Integer.MAX_VALUE, Long.MAX_VALUE);
    }

    public boolean exceedsTurns(int turns) {
        return turns > maxTurns;
    }

    public boolean exceedsToolCalls(int toolCalls) {
        return toolCalls > maxToolCalls;
    }

    public boolean exceedsInputTokens(long inputTokens) {
        return inputTokens > maxInputTokens;
    }
}
