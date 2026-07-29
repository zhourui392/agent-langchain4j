package com.anthropic.agentkit.domain.suspension;

import com.anthropic.agentkit.domain.agent.RunId;
import com.anthropic.agentkit.domain.agent.WorkspaceId;
import com.anthropic.agentkit.domain.conversation.SessionId;

import java.util.Objects;

/** Scope of the new run segment attempting to claim a suspension. */
public record ResumeScope(
        RunId runId,
        SessionId sessionId,
        WorkspaceId workspaceId) {

    public ResumeScope {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(workspaceId, "workspaceId");
    }
}
