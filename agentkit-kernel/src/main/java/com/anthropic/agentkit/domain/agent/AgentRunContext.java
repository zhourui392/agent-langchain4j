package com.anthropic.agentkit.domain.agent;

import com.anthropic.agentkit.domain.conversation.CancellationToken;
import com.anthropic.agentkit.domain.conversation.SessionId;
import com.anthropic.agentkit.domain.port.SecretProvider;
import com.anthropic.agentkit.domain.tool.ExecutionContext;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

/** The single dynamic scope shared by every action in one agent run. */
public record AgentRunContext(
        RunId runId,
        SessionId sessionId,
        WorkspaceId workspaceId,
        Path workspaceRoot,
        CancellationToken cancellation,
        AgentBudget budget,
        SecretProvider secretProvider,
        AgentRunLimits limits,
        AgentBudgetState budgetState,
        Optional<SubAgentExecutionScope> subAgentScope) {

    public AgentRunContext {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(workspaceId, "workspaceId");
        Objects.requireNonNull(workspaceRoot, "workspaceRoot");
        Objects.requireNonNull(cancellation, "cancellation");
        Objects.requireNonNull(budget, "budget");
        Objects.requireNonNull(secretProvider, "secretProvider");
        Objects.requireNonNull(limits, "limits");
        Objects.requireNonNull(budgetState, "budgetState");
        Objects.requireNonNull(subAgentScope, "subAgentScope");
        workspaceRoot = workspaceRoot.toAbsolutePath().normalize();
    }

    public static AgentRunContext of(RunId runId, SessionId sessionId,
                                     WorkspaceId workspaceId, Path workspaceRoot,
                                     CancellationToken cancellation, AgentBudget budget) {
        return of(runId, sessionId, workspaceId, workspaceRoot,
                cancellation, budget, SecretProvider.none());
    }

    public static AgentRunContext of(RunId runId, SessionId sessionId,
                                     WorkspaceId workspaceId, Path workspaceRoot,
                                     CancellationToken cancellation, AgentBudget budget,
                                     SecretProvider secretProvider) {
        return new AgentRunContext(runId, sessionId, workspaceId, workspaceRoot,
                cancellation, budget, secretProvider,
                AgentRunLimits.defaults(), new AgentBudgetState(), Optional.empty());
    }

    public static AgentRunContext at(Path workspaceRoot) {
        return create(SessionId.fresh(), workspaceRoot, new CancellationToken(), AgentBudget.unlimited());
    }

    public static AgentRunContext create(SessionId sessionId, Path workspaceRoot,
                                         CancellationToken cancellation, AgentBudget budget) {
        return create(sessionId, workspaceRoot, cancellation, budget, SecretProvider.none());
    }

    public static AgentRunContext create(SessionId sessionId, Path workspaceRoot,
                                         CancellationToken cancellation, AgentBudget budget,
                                         SecretProvider secretProvider) {
        return of(RunId.fresh(), sessionId, WorkspaceId.fromPath(workspaceRoot),
                workspaceRoot, cancellation, budget, secretProvider);
    }

    public ExecutionContext executionContext() {
        return ExecutionContext.of(
                runId, sessionId, workspaceId, workspaceRoot, cancellation, budget,
                secretProvider, limits, budgetState, subAgentScope);
    }

    public AgentRunContext withLimits(AgentRunLimits requested) {
        return new AgentRunContext(
                runId, sessionId, workspaceId, workspaceRoot, cancellation, budget,
                secretProvider, Objects.requireNonNull(requested, "requested"),
                budgetState, subAgentScope);
    }

    public BudgetConsumption budgetConsumption() {
        return budgetState.consumption();
    }

    public static AgentRunContext childRun(
            ExecutionContext parent, SessionId childSession, RunId childRun,
            CancellationToken childCancellation, AgentBudget requestedBudget,
            AgentRunLimits requestedLimits, SubAgentExecutionScope childScope) {
        Objects.requireNonNull(parent, "parent");
        return new AgentRunContext(
                childRun, childSession, parent.workspaceId(), parent.cwd(), childCancellation,
                parent.budget().narrowedBy(requestedBudget), parent.secretProvider(),
                parent.limits().narrowedBy(requestedLimits),
                parent.budgetState().child(parent.budget()), Optional.of(childScope));
    }
}
