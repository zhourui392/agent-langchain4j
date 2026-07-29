package com.anthropic.agentkit.domain.run;

import com.anthropic.agentkit.domain.agent.RunId;
import com.anthropic.agentkit.domain.agent.WorkspaceId;
import com.anthropic.agentkit.domain.conversation.SessionId;

import java.time.Instant;
import java.util.Objects;

/** Versioned identity and ordering metadata shared by every run event. */
public record RunEventMetadata(
        int schemaVersion,
        RunId runId,
        SessionId sessionId,
        WorkspaceId workspaceId,
        long sequence,
        Instant occurredAt) {

    public RunEventMetadata {
        if (schemaVersion <= 0) {
            throw new IllegalArgumentException("schemaVersion must be positive");
        }
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(workspaceId, "workspaceId");
        if (sequence <= 0) {
            throw new IllegalArgumentException("event sequence must be positive");
        }
        Objects.requireNonNull(occurredAt, "occurredAt");
    }
}
