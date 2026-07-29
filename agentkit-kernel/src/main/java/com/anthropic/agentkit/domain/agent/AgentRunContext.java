package com.anthropic.agentkit.domain.agent;

import com.anthropic.agentkit.domain.conversation.CancellationToken;
import com.anthropic.agentkit.domain.conversation.SessionId;
import com.anthropic.agentkit.domain.tool.ExecutionContext;

import java.nio.file.Path;
import java.util.Objects;

/** The single dynamic scope shared by every action in one agent run. */
public record AgentRunContext(
        RunId runId,
        SessionId sessionId,
        WorkspaceId workspaceId,
        Path workspaceRoot,
        CancellationToken cancellation,
        AgentBudget budget) {

    public AgentRunContext {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(workspaceId, "workspaceId");
        Objects.requireNonNull(workspaceRoot, "workspaceRoot");
        Objects.requireNonNull(cancellation, "cancellation");
        Objects.requireNonNull(budget, "budget");
        workspaceRoot = workspaceRoot.toAbsolutePath().normalize();
    }

    public static AgentRunContext of(RunId runId, SessionId sessionId,
                                     WorkspaceId workspaceId, Path workspaceRoot,
                                     CancellationToken cancellation, AgentBudget budget) {
        return new AgentRunContext(runId, sessionId, workspaceId, workspaceRoot, cancellation, budget);
    }

    public static AgentRunContext at(Path workspaceRoot) {
        return create(SessionId.fresh(), workspaceRoot, new CancellationToken(), AgentBudget.unlimited());
    }

    public static AgentRunContext create(SessionId sessionId, Path workspaceRoot,
                                         CancellationToken cancellation, AgentBudget budget) {
        return of(RunId.fresh(), sessionId, WorkspaceId.fromPath(workspaceRoot),
                workspaceRoot, cancellation, budget);
    }

    public ExecutionContext executionContext() {
        return ExecutionContext.of(runId, workspaceId, workspaceRoot, cancellation, budget);
    }
}
