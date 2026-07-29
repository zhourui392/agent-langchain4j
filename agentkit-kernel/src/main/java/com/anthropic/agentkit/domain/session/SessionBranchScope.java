package com.anthropic.agentkit.domain.session;

import com.anthropic.agentkit.domain.agent.WorkspaceId;
import com.anthropic.agentkit.domain.checkpoint.CheckpointOwner;
import com.anthropic.agentkit.domain.conversation.SessionId;

import java.util.Objects;

/** Security and correctness ownership shared by a branch and its parent. */
public record SessionBranchScope(SessionId sessionId, WorkspaceId workspaceId) {

    public SessionBranchScope {
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(workspaceId, "workspaceId");
    }

    public CheckpointOwner checkpointOwner() {
        return new CheckpointOwner(sessionId, workspaceId);
    }
}
