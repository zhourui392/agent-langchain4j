package com.anthropic.agentkit.domain.session;

/** Uniform not-found/scope-mismatch failure that does not disclose branch ownership. */
public final class SessionBranchUnavailableException extends RuntimeException {

    public SessionBranchUnavailableException() {
        super("session branch is unavailable for the requested scope");
    }
}
