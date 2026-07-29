package com.anthropic.agentkit.domain.port;

/** Provider-neutral signal that a request exceeded the model context window. */
public final class ContextWindowExceededException extends RuntimeException {

    public ContextWindowExceededException(String message) {
        super(message);
    }

    public ContextWindowExceededException(String message, Throwable cause) {
        super(message, cause);
    }
}
