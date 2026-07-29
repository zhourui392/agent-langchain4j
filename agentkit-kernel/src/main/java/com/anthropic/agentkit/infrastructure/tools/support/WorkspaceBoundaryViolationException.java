package com.anthropic.agentkit.infrastructure.tools.support;

/** Raised when a requested path resolves outside the active workspace. */
public final class WorkspaceBoundaryViolationException extends RuntimeException {

    public WorkspaceBoundaryViolationException(String message) {
        super(message);
    }

    public WorkspaceBoundaryViolationException(String message, Throwable cause) {
        super(message, cause);
    }
}
