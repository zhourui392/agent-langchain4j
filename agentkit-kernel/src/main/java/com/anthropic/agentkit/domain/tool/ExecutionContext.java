package com.anthropic.agentkit.domain.tool;

import com.anthropic.agentkit.domain.agent.AgentBudget;
import com.anthropic.agentkit.domain.agent.AgentBudgetState;
import com.anthropic.agentkit.domain.agent.AgentRunLimits;
import com.anthropic.agentkit.domain.agent.BudgetConsumption;
import com.anthropic.agentkit.domain.agent.RunId;
import com.anthropic.agentkit.domain.agent.SubAgentExecutionScope;
import com.anthropic.agentkit.domain.agent.WorkspaceId;
import com.anthropic.agentkit.domain.conversation.CancellationToken;
import com.anthropic.agentkit.domain.port.SecretProvider;
import com.anthropic.agentkit.domain.port.SecretScope;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

public record ExecutionContext(
        RunId runId,
        WorkspaceId workspaceId,
        Path cwd,
        CancellationToken cancellation,
        AgentBudget budget,
        SecretProvider secretProvider,
        AgentRunLimits limits,
        AgentBudgetState budgetState,
        Optional<SubAgentExecutionScope> subAgentScope) {

    public ExecutionContext {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(workspaceId, "workspaceId");
        Objects.requireNonNull(cwd, "cwd");
        Objects.requireNonNull(cancellation, "cancellation");
        Objects.requireNonNull(budget, "budget");
        Objects.requireNonNull(secretProvider, "secretProvider");
        Objects.requireNonNull(limits, "limits");
        Objects.requireNonNull(budgetState, "budgetState");
        Objects.requireNonNull(subAgentScope, "subAgentScope");
        cwd = cwd.toAbsolutePath().normalize();
    }

    public static ExecutionContext at(Path cwd) {
        return of(cwd, new CancellationToken());
    }

    public static ExecutionContext of(Path cwd, CancellationToken cancellation) {
        return of(RunId.fresh(), WorkspaceId.fromPath(cwd), cwd, cancellation, AgentBudget.unlimited());
    }

    public static ExecutionContext of(RunId runId, WorkspaceId workspaceId, Path cwd,
                                      CancellationToken cancellation, AgentBudget budget) {
        return of(runId, workspaceId, cwd, cancellation, budget, SecretProvider.none());
    }

    public static ExecutionContext of(RunId runId, WorkspaceId workspaceId, Path cwd,
                                      CancellationToken cancellation, AgentBudget budget,
                                      SecretProvider secretProvider) {
        return new ExecutionContext(
                runId, workspaceId, cwd, cancellation, budget, secretProvider,
                AgentRunLimits.defaults(), new AgentBudgetState(), Optional.empty());
    }

    public static ExecutionContext of(RunId runId, WorkspaceId workspaceId, Path cwd,
                                      CancellationToken cancellation, AgentBudget budget,
                                      SecretProvider secretProvider, AgentRunLimits limits,
                                      AgentBudgetState budgetState) {
        return new ExecutionContext(
                runId, workspaceId, cwd, cancellation, budget, secretProvider,
                limits, budgetState, Optional.empty());
    }

    public static ExecutionContext of(RunId runId, WorkspaceId workspaceId, Path cwd,
                                      CancellationToken cancellation, AgentBudget budget,
                                      SecretProvider secretProvider, AgentRunLimits limits,
                                      AgentBudgetState budgetState,
                                      Optional<SubAgentExecutionScope> subAgentScope) {
        return new ExecutionContext(
                runId, workspaceId, cwd, cancellation, budget, secretProvider,
                limits, budgetState, subAgentScope);
    }

    public Optional<String> secret(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("secret name must not be blank");
        }
        return secretProvider.find(new SecretScope(runId, workspaceId), name);
    }

    public BudgetConsumption budgetConsumption() {
        return budgetState.consumption();
    }

    public ExecutionContext withSubAgentScope(SubAgentExecutionScope scope) {
        return new ExecutionContext(
                runId, workspaceId, cwd, cancellation, budget, secretProvider,
                limits, budgetState, Optional.of(Objects.requireNonNull(scope, "scope")));
    }
}
