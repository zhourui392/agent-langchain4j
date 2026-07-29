package com.anthropic.agentkit.domain.checkpoint;

import com.anthropic.agentkit.domain.agent.WorkspaceId;
import com.anthropic.agentkit.domain.conversation.SessionId;

import java.util.Objects;

/** Session/workspace ownership required for checkpoint access. */
public record CheckpointOwner(SessionId sessionId, WorkspaceId workspaceId) {

    public CheckpointOwner {
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(workspaceId, "workspaceId");
    }
}
