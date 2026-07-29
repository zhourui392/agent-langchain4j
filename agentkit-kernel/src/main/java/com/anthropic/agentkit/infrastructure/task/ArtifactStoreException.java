package com.anthropic.agentkit.infrastructure.task;

/** Durable artifact storage failed independently of task execution. */
public final class ArtifactStoreException extends RuntimeException {

    ArtifactStoreException(String message, Throwable cause) {
        super(message, cause);
    }
}
