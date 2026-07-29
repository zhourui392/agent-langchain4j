package com.anthropic.agentkit.domain.port;

import com.anthropic.agentkit.domain.task.ArtifactReference;
import com.anthropic.agentkit.domain.task.TaskScope;

import java.util.Optional;

/** Scoped durable storage for governed full background-task output. */
public interface ArtifactStore {

    ArtifactReference write(TaskScope scope, String content);

    Optional<String> read(TaskScope scope, ArtifactReference reference);
}
