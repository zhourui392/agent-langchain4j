package com.anthropic.agentkit.domain.port;

/** Fatal persistence failure while saving or claiming a run suspension. */
public final class RunSuspensionStoreException extends RuntimeException {

    public RunSuspensionStoreException(String message, Throwable cause) {
        super(message, cause);
    }
}
