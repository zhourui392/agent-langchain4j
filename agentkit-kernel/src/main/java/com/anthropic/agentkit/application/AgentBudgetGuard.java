package com.anthropic.agentkit.application;

import com.anthropic.agentkit.domain.agent.AgentBudget;
import com.anthropic.agentkit.domain.agent.AgentBudgetExceededException;
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
    private int turns;
    private int toolCalls;
    private long inputTokens;
    private long outputTokens;
    private long cacheReadInputTokens;

    AgentBudgetGuard(AgentBudget budget) {
        this.budget = Objects.requireNonNull(budget, "budget");
    }

    void reserveTurn() {
        int nextTurns = turns + 1;
        if (budget.exceedsTurns(nextTurns)) {
            throw new AgentBudgetExceededException("agent budget exceeded: maxTurns=" + budget.maxTurns());
        }
        turns = nextTurns;
    }

    void reserveToolCalls(int requestedToolCalls) {
        int nextToolCalls = toolCalls + requestedToolCalls;
        if (budget.exceedsToolCalls(nextToolCalls)) {
            throw new AgentBudgetExceededException(
                    "agent budget exceeded: maxToolCalls=" + budget.maxToolCalls());
        }
        toolCalls = nextToolCalls;
    }

    void recordInputTokens(int tokens) {
        if (tokens > 0) {
            inputTokens += tokens;
        }
    }

    void recordUsage(int input, int output, int cacheReadInput) {
        recordInputTokens(input);
        if (output > 0) {
            outputTokens += output;
        }
        if (cacheReadInput > 0) {
            cacheReadInputTokens += cacheReadInput;
        }
    }

    void ensureInputTokensWithinBudget() {
        if (budget.exceedsInputTokens(inputTokens)) {
            throw new AgentBudgetExceededException(
                    "agent budget exceeded: maxInputTokens=" + budget.maxInputTokens());
        }
    }

    AgentUsage usage() {
        return new AgentUsage(inputTokens, outputTokens, cacheReadInputTokens);
    }

    BudgetConsumption consumption() {
        return new BudgetConsumption(turns, toolCalls, inputTokens);
    }
}
