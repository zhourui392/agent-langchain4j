package com.anthropic.agentkit.domain.task;

import java.util.Objects;
import java.util.Optional;

/** Immutable status projection of one background task. */
public record TaskSnapshot(
        TaskId id,
        TaskState state,
        String preview,
        long outputCharacters,
        Optional<ArtifactReference> artifact) {

    public TaskSnapshot {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(preview, "preview");
        Objects.requireNonNull(artifact, "artifact");
        if (outputCharacters < 0) {
            throw new IllegalArgumentException("outputCharacters must not be negative");
        }
    }
}
