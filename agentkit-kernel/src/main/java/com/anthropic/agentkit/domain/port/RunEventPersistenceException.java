package com.anthropic.agentkit.domain.port;

/** Fatal failure to persist an ordered run fact. */
public final class RunEventPersistenceException extends RuntimeException {

    public RunEventPersistenceException(String message, Throwable cause) {
        super(message, cause);
    }
}
