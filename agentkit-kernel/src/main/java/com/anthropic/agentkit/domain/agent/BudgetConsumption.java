package com.anthropic.agentkit.domain.agent;

/** Budget units accepted and consumed by one run. */
public record BudgetConsumption(
        int turns,
        int toolCalls,
        long inputTokens,
        long outputTokens,
        long outputCharacters) {

    public BudgetConsumption(int turns, int toolCalls, long inputTokens) {
        this(turns, toolCalls, inputTokens, 0, 0);
    }

    public BudgetConsumption {
        if (turns < 0 || toolCalls < 0 || inputTokens < 0
                || outputTokens < 0 || outputCharacters < 0) {
            throw new IllegalArgumentException("budget consumption must be non-negative");
        }
    }

    public static BudgetConsumption zero() {
        return new BudgetConsumption(0, 0, 0, 0, 0);
    }
}
