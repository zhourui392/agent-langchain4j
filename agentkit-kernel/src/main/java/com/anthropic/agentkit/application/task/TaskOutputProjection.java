package com.anthropic.agentkit.application.task;

import com.anthropic.agentkit.domain.task.ArtifactReference;
import com.anthropic.agentkit.domain.task.TaskId;
import com.anthropic.agentkit.domain.task.TaskSnapshot;
import com.anthropic.agentkit.domain.task.TaskState;

import java.util.Objects;
import java.util.Optional;

/** Application projection of governed output for one task lifecycle state. */
record TaskOutputProjection(
        TaskState state,
        String preview,
        long outputCharacters,
        Optional<ArtifactReference> artifact) {

    TaskOutputProjection {
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(preview, "preview");
        Objects.requireNonNull(artifact, "artifact");
        if (outputCharacters < 0) {
            throw new IllegalArgumentException("outputCharacters must not be negative");
        }
    }

    static TaskOutputProjection initial(TaskState state) {
        return new TaskOutputProjection(state, "", 0, Optional.empty());
    }

    TaskSnapshot snapshot(TaskId id) {
        return new TaskSnapshot(id, state, preview, outputCharacters, artifact);
    }
}
