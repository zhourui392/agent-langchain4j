package com.anthropic.agentkit.domain.agent;

/**
 * Generic execution budget for a kernel agent run.
 *
 * @author zhourui(V33215020)
 * @since 2026-06-11
 */
public record AgentBudget(
        int maxTurns,
        int maxToolCalls,
        long maxInputTokens,
        long maxOutputTokens,
        long maxOutputCharacters) {

    public AgentBudget(int maxTurns, int maxToolCalls, long maxInputTokens) {
        this(maxTurns, maxToolCalls, maxInputTokens, Long.MAX_VALUE, Long.MAX_VALUE);
    }

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
        if (maxOutputTokens < 0) {
            throw new IllegalArgumentException("maxOutputTokens must be >= 0");
        }
        if (maxOutputCharacters < 0) {
            throw new IllegalArgumentException("maxOutputCharacters must be >= 0");
        }
    }

    public static AgentBudget of(int maxTurns, int maxToolCalls, long maxInputTokens) {
        return new AgentBudget(maxTurns, maxToolCalls, maxInputTokens);
    }

    public static AgentBudget of(int maxTurns, int maxToolCalls, long maxInputTokens,
                                 long maxOutputTokens, long maxOutputCharacters) {
        return new AgentBudget(maxTurns, maxToolCalls, maxInputTokens,
                maxOutputTokens, maxOutputCharacters);
    }

    public static AgentBudget unlimited() {
        return new AgentBudget(Integer.MAX_VALUE, Integer.MAX_VALUE, Long.MAX_VALUE,
                Long.MAX_VALUE, Long.MAX_VALUE);
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

    public boolean exceedsOutputTokens(long outputTokens) {
        return outputTokens > maxOutputTokens;
    }

    public boolean exceedsOutputCharacters(long outputCharacters) {
        return outputCharacters > maxOutputCharacters;
    }

    public AgentBudget narrowedBy(AgentBudget requested) {
        return new AgentBudget(
                Math.min(maxTurns, requested.maxTurns),
                Math.min(maxToolCalls, requested.maxToolCalls),
                Math.min(maxInputTokens, requested.maxInputTokens),
                Math.min(maxOutputTokens, requested.maxOutputTokens),
                Math.min(maxOutputCharacters, requested.maxOutputCharacters));
    }
}
