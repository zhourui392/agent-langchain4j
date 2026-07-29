package com.anthropic.agentkit.domain.port;

import com.anthropic.agentkit.domain.task.ArtifactReference;
import com.anthropic.agentkit.domain.task.TaskScope;

import java.util.Optional;

/** Scoped durable storage for governed full background-task output. */
public interface ArtifactStore {

    /**
     * Stores governed content in the supplied scope.
     *
     * @throws ArtifactStoreException when the configured medium cannot persist the artifact
     */
    ArtifactReference write(TaskScope scope, String content);

    /**
     * Reads an artifact only when both the supplied scope and reference are valid.
     *
     * @throws ArtifactStoreException when the configured medium cannot read the artifact
     */
    Optional<String> read(TaskScope scope, ArtifactReference reference);
}
