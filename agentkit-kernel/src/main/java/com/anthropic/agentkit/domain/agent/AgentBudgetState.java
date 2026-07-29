package com.anthropic.agentkit.domain.agent;

/** Thread-safe consumption ledger shared by a parent run and all child agents. */
public final class AgentBudgetState {

    private int turns;
    private int toolCalls;
    private long inputTokens;
    private long outputTokens;
    private long outputCharacters;
    private long cacheReadInputTokens;

    public synchronized void reserveTurn(AgentBudget budget) {
        if (turns == Integer.MAX_VALUE || budget.exceedsTurns(turns + 1)) {
            throw exceeded("maxTurns", budget.maxTurns());
        }
        turns++;
    }

    public synchronized void reserveToolCalls(AgentBudget budget, int requested) {
        long next = (long) toolCalls + requested;
        if (next > Integer.MAX_VALUE || budget.exceedsToolCalls((int) next)) {
            throw exceeded("maxToolCalls", budget.maxToolCalls());
        }
        toolCalls = (int) next;
    }

    public synchronized void recordUsage(int input, int output, int cacheReadInput) {
        inputTokens += positive(input);
        outputTokens += positive(output);
        cacheReadInputTokens += positive(cacheReadInput);
    }

    public synchronized void recordOutputCharacters(int characters) {
        outputCharacters += positive(characters);
    }

    public synchronized void ensureWithin(AgentBudget budget) {
        if (budget.exceedsInputTokens(inputTokens)) {
            throw exceeded("maxInputTokens", budget.maxInputTokens());
        }
        if (budget.exceedsOutputTokens(outputTokens)) {
            throw exceeded("maxOutputTokens", budget.maxOutputTokens());
        }
        if (budget.exceedsOutputCharacters(outputCharacters)) {
            throw exceeded("maxOutputCharacters", budget.maxOutputCharacters());
        }
    }

    public synchronized AgentUsage usage() {
        return new AgentUsage(inputTokens, outputTokens, cacheReadInputTokens);
    }

    public synchronized BudgetConsumption consumption() {
        return new BudgetConsumption(
                turns, toolCalls, inputTokens, outputTokens, outputCharacters);
    }

    private static long positive(long value) {
        return Math.max(0, value);
    }

    private static AgentBudgetExceededException exceeded(String name, long limit) {
        return new AgentBudgetExceededException("agent budget exceeded: " + name + "=" + limit);
    }
}
