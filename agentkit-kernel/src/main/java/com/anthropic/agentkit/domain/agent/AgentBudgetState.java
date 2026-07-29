package com.anthropic.agentkit.domain.agent;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Thread-safe hierarchical ledger: local consumption also charges every parent scope. */
public final class AgentBudgetState {

    private final Object lock;
    private final Counter local;
    private final List<BoundScope> ancestors;

    public AgentBudgetState() {
        this(new Object(), new Counter(), List.of());
    }

    private AgentBudgetState(Object lock, Counter local, List<BoundScope> ancestors) {
        this.lock = lock;
        this.local = local;
        this.ancestors = List.copyOf(ancestors);
    }

    public AgentBudgetState child(AgentBudget parentBudget) {
        Objects.requireNonNull(parentBudget, "parentBudget");
        List<BoundScope> inherited = new ArrayList<>(ancestors);
        inherited.add(new BoundScope(local, parentBudget));
        return new AgentBudgetState(lock, new Counter(), inherited);
    }

    public void reserveTurn(AgentBudget budget) {
        synchronized (lock) {
            ancestors.forEach(BoundScope::ensureTurnAvailable);
            ensureTurnAvailable(local, budget);
            ancestors.forEach(scope -> scope.counter.turns++);
            local.turns++;
        }
    }

    public void reserveToolCalls(AgentBudget budget, int requested) {
        synchronized (lock) {
            ancestors.forEach(scope -> scope.ensureToolCallsAvailable(requested));
            ensureToolCallsAvailable(local, budget, requested);
            ancestors.forEach(scope -> scope.counter.toolCalls += requested);
            local.toolCalls += requested;
        }
    }

    public void recordUsage(int input, int output, int cacheReadInput) {
        synchronized (lock) {
            ancestors.forEach(scope -> scope.counter.recordUsage(input, output, cacheReadInput));
            local.recordUsage(input, output, cacheReadInput);
        }
    }

    public void recordOutputCharacters(int characters) {
        synchronized (lock) {
            ancestors.forEach(scope -> scope.counter.recordOutputCharacters(characters));
            local.recordOutputCharacters(characters);
        }
    }

    public void ensureWithin(AgentBudget budget) {
        synchronized (lock) {
            ancestors.forEach(BoundScope::ensureWithin);
            ensureWithin(local, budget);
        }
    }

    public AgentUsage usage() {
        synchronized (lock) {
            return local.usage();
        }
    }

    public BudgetConsumption consumption() {
        synchronized (lock) {
            return local.consumption();
        }
    }

    private static void ensureTurnAvailable(Counter counter, AgentBudget budget) {
        if (counter.turns == Integer.MAX_VALUE || budget.exceedsTurns(counter.turns + 1)) {
            throw exceeded("maxTurns", budget.maxTurns());
        }
    }

    private static void ensureToolCallsAvailable(
            Counter counter, AgentBudget budget, int requested) {
        long next = (long) counter.toolCalls + requested;
        if (next > Integer.MAX_VALUE || budget.exceedsToolCalls((int) next)) {
            throw exceeded("maxToolCalls", budget.maxToolCalls());
        }
    }

    private static void ensureWithin(Counter counter, AgentBudget budget) {
        if (budget.exceedsInputTokens(counter.inputTokens)) {
            throw exceeded("maxInputTokens", budget.maxInputTokens());
        }
        if (budget.exceedsOutputTokens(counter.outputTokens)) {
            throw exceeded("maxOutputTokens", budget.maxOutputTokens());
        }
        if (budget.exceedsOutputCharacters(counter.outputCharacters)) {
            throw exceeded("maxOutputCharacters", budget.maxOutputCharacters());
        }
    }

    private static AgentBudgetExceededException exceeded(String name, long limit) {
        return new AgentBudgetExceededException("agent budget exceeded: " + name + "=" + limit);
    }

    private record BoundScope(Counter counter, AgentBudget budget) {
        private void ensureTurnAvailable() {
            AgentBudgetState.ensureTurnAvailable(counter, budget);
        }

        private void ensureToolCallsAvailable(int requested) {
            AgentBudgetState.ensureToolCallsAvailable(counter, budget, requested);
        }

        private void ensureWithin() {
            AgentBudgetState.ensureWithin(counter, budget);
        }
    }

    private static final class Counter {
        private int turns;
        private int toolCalls;
        private long inputTokens;
        private long outputTokens;
        private long outputCharacters;
        private long cacheReadInputTokens;

        private void recordUsage(int input, int output, int cacheReadInput) {
            inputTokens += positive(input);
            outputTokens += positive(output);
            cacheReadInputTokens += positive(cacheReadInput);
        }

        private void recordOutputCharacters(int characters) {
            outputCharacters += positive(characters);
        }

        private AgentUsage usage() {
            return new AgentUsage(inputTokens, outputTokens, cacheReadInputTokens);
        }

        private BudgetConsumption consumption() {
            return new BudgetConsumption(
                    turns, toolCalls, inputTokens, outputTokens, outputCharacters);
        }

        private static long positive(long value) {
            return Math.max(0, value);
        }
    }
}
