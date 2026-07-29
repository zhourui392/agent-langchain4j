package com.anthropic.agentkit.domain.tool;

import com.anthropic.agentkit.domain.agent.AgentBudget;
import com.anthropic.agentkit.domain.agent.RunId;
import com.anthropic.agentkit.domain.agent.WorkspaceId;
import com.anthropic.agentkit.domain.conversation.CancellationToken;

import java.nio.file.Path;
import java.util.Objects;

public record ExecutionContext(
        RunId runId,
        WorkspaceId workspaceId,
        Path cwd,
        CancellationToken cancellation,
        AgentBudget budget) {

    public ExecutionContext {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(workspaceId, "workspaceId");
        Objects.requireNonNull(cwd, "cwd");
        Objects.requireNonNull(cancellation, "cancellation");
        Objects.requireNonNull(budget, "budget");
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
        return new ExecutionContext(runId, workspaceId, cwd, cancellation, budget);
    }
}
