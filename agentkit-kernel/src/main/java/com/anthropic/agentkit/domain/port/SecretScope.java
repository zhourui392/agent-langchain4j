package com.anthropic.agentkit.domain.port;

import com.anthropic.agentkit.domain.agent.RunId;
import com.anthropic.agentkit.domain.agent.WorkspaceId;

import java.util.Objects;

/** Explicit run and workspace scope for a secret lookup. */
public record SecretScope(RunId runId, WorkspaceId workspaceId) {

    public SecretScope {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(workspaceId, "workspaceId");
    }
}
