package com.anthropic.agentkit.domain.task;

import com.anthropic.agentkit.domain.agent.RunId;
import com.anthropic.agentkit.domain.agent.WorkspaceId;
import com.anthropic.agentkit.domain.tool.ExecutionContext;

import java.util.Objects;

/** Explicit run/workspace ownership boundary for a background task and its artifacts. */
public record TaskScope(RunId runId, WorkspaceId workspaceId) {

    public TaskScope {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(workspaceId, "workspaceId");
    }

    public static TaskScope from(ExecutionContext context) {
        Objects.requireNonNull(context, "context");
        return new TaskScope(context.runId(), context.workspaceId());
    }

    public boolean owns(ExecutionContext context) {
        return equals(from(context));
    }
}
