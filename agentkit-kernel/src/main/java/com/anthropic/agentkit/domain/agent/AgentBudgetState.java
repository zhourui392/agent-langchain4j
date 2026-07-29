package com.anthropic.agentkit.domain.agent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

    public void reserveLlmCall(AgentBudget budget, ModelIdentity model) {
        Objects.requireNonNull(model, "model");
        synchronized (lock) {
            ancestors.forEach(BoundScope::ensureLlmCallAvailable);
            ensureLlmCallAvailable(local, budget);
            ancestors.forEach(scope -> scope.counter.recordModelAttempt(model));
            local.recordModelAttempt(model);
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

    public void recordUsage(
            ModelIdentity model, int input, int output, int cacheReadInput) {
        Objects.requireNonNull(model, "model");
        synchronized (lock) {
            ancestors.forEach(scope -> scope.counter.recordUsage(
                    model, input, output, cacheReadInput));
            local.recordUsage(model, input, output, cacheReadInput);
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

    private static void ensureLlmCallAvailable(Counter counter, AgentBudget budget) {
        if (counter.llmCalls == Integer.MAX_VALUE
                || budget.exceedsLlmCalls(counter.llmCalls + 1)) {
            throw exceeded("maxLlmCalls", budget.maxLlmCalls());
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

        private void ensureLlmCallAvailable() {
            AgentBudgetState.ensureLlmCallAvailable(counter, budget);
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
        private int llmCalls;
        private final Map<ModelIdentity, ModelUsageCounter> modelUsage =
                new LinkedHashMap<>();

        private void recordModelAttempt(ModelIdentity model) {
            llmCalls++;
            modelUsage.computeIfAbsent(model, ignored -> new ModelUsageCounter()).attempts++;
        }

        private void recordUsage(int input, int output, int cacheReadInput) {
            inputTokens += positive(input);
            outputTokens += positive(output);
            cacheReadInputTokens += positive(cacheReadInput);
        }

        private void recordUsage(
                ModelIdentity model, int input, int output, int cacheReadInput) {
            recordUsage(input, output, cacheReadInput);
            modelUsage.computeIfAbsent(model, ignored -> new ModelUsageCounter())
                    .recordUsage(input, output, cacheReadInput);
        }

        private void recordOutputCharacters(int characters) {
            outputCharacters += positive(characters);
        }

        private AgentUsage usage() {
            List<ModelUsage> breakdown = modelUsage.entrySet().stream()
                    .map(entry -> entry.getValue().toUsage(entry.getKey()))
                    .toList();
            return new AgentUsage(
                    inputTokens, outputTokens, cacheReadInputTokens, breakdown);
        }

        private BudgetConsumption consumption() {
            return new BudgetConsumption(
                    turns, toolCalls, inputTokens, outputTokens,
                    outputCharacters, llmCalls);
        }

        private static long positive(long value) {
            return Math.max(0, value);
        }
    }

    private static final class ModelUsageCounter {
        private int attempts;
        private long inputTokens;
        private long outputTokens;
        private long cacheReadInputTokens;

        private void recordUsage(int input, int output, int cacheReadInput) {
            inputTokens += Math.max(0, input);
            outputTokens += Math.max(0, output);
            cacheReadInputTokens += Math.max(0, cacheReadInput);
        }

        private ModelUsage toUsage(ModelIdentity model) {
            return new ModelUsage(model, attempts, inputTokens,
                    outputTokens, cacheReadInputTokens);
        }
    }
}
