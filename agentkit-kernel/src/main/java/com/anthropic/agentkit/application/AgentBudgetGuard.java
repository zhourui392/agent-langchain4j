package com.anthropic.agentkit.application;

import com.anthropic.agentkit.domain.agent.AgentBudget;
import com.anthropic.agentkit.domain.agent.AgentBudgetExceededException;
import com.anthropic.agentkit.domain.agent.AgentBudgetState;
import com.anthropic.agentkit.domain.agent.AgentUsage;
import com.anthropic.agentkit.domain.agent.BudgetConsumption;

import java.util.Objects;

/**
 * Tracks one run's consumed agent budget.
 *
 * @author zhourui(V33215020)
 * @since 2026-06-11
 */
final class AgentBudgetGuard {

    private final AgentBudget budget;
    private final AgentBudgetState state;

    AgentBudgetGuard(AgentBudget budget, AgentBudgetState state) {
        this.budget = Objects.requireNonNull(budget, "budget");
        this.state = Objects.requireNonNull(state, "state");
    }

    void reserveTurn() {
        state.reserveTurn(budget);
    }

    void reserveToolCalls(int requestedToolCalls) {
        state.reserveToolCalls(budget, requestedToolCalls);
    }

    void recordInputTokens(int tokens) {
        state.recordUsage(tokens, 0, 0);
    }

    void recordUsage(int input, int output, int cacheReadInput) {
        state.recordUsage(input, output, cacheReadInput);
    }

    void recordOutputCharacters(int characters) {
        state.recordOutputCharacters(characters);
    }

    void ensureInputTokensWithinBudget() {
        state.ensureWithin(budget);
    }

    AgentUsage usage() {
        return state.usage();
    }

    BudgetConsumption consumption() {
        return state.consumption();
    }
}
