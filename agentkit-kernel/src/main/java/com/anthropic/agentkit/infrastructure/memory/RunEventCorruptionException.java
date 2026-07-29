package com.anthropic.agentkit.infrastructure.memory;

/** A run event log is corrupt somewhere other than an incomplete final record. */
public final class RunEventCorruptionException extends RuntimeException {

    public RunEventCorruptionException(String message, Throwable cause) {
        super(message, cause);
    }

    public RunEventCorruptionException(String message) {
        super(message);
    }
}
