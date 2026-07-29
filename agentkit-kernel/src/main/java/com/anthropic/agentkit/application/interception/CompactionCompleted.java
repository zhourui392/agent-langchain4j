package com.anthropic.agentkit.application.interception;

import com.anthropic.agentkit.domain.conversation.CompactionBoundary;

import java.util.Objects;

/** Observed installation of a new compaction boundary. */
public record CompactionCompleted(
        CompactionContext attempt, CompactionBoundary boundary) {

    public CompactionCompleted {
        Objects.requireNonNull(attempt, "attempt");
        Objects.requireNonNull(boundary, "boundary");
    }
}
