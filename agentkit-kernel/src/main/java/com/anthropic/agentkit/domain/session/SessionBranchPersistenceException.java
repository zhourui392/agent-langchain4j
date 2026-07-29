package com.anthropic.agentkit.domain.session;

/** Durable branch journal could not be read or appended safely. */
public final class SessionBranchPersistenceException extends RuntimeException {

    public SessionBranchPersistenceException(String message, Throwable cause) {
        super(message, cause);
    }
}
