package com.anthropic.agentkit.domain.agent;

/** Budget units accepted and consumed by one run. */
public record BudgetConsumption(int turns, int toolCalls, long inputTokens) {

    public BudgetConsumption {
        if (turns < 0 || toolCalls < 0 || inputTokens < 0) {
            throw new IllegalArgumentException("budget consumption must be non-negative");
        }
    }

    public static BudgetConsumption zero() {
        return new BudgetConsumption(0, 0, 0);
    }
}
