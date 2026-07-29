package com.anthropic.agentkit.domain.checkpoint;

/** Explicit failure while capturing or restoring a kernel-managed file. */
public final class FileCheckpointException extends RuntimeException {

    public FileCheckpointException(String message) {
        super(message);
    }

    public FileCheckpointException(String message, Throwable cause) {
        super(message, cause);
    }
}
