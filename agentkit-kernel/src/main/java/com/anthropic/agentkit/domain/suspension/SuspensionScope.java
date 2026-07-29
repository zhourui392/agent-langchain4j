package com.anthropic.agentkit.domain.suspension;

import com.anthropic.agentkit.domain.agent.RunId;
import com.anthropic.agentkit.domain.agent.WorkspaceId;
import com.anthropic.agentkit.domain.conversation.SessionId;

import java.util.Objects;

/** Stable ownership of a pending request and its originating run segment. */
public record SuspensionScope(
        SessionId sessionId,
        WorkspaceId workspaceId,
        RunId originatingRunId) {

    public SuspensionScope {
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(workspaceId, "workspaceId");
        Objects.requireNonNull(originatingRunId, "originatingRunId");
    }
}
