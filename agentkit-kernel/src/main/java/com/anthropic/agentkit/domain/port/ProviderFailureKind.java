package com.anthropic.agentkit.domain.port;

/** Provider-neutral failure classification used by retry policy. */
public enum ProviderFailureKind {
    TRANSIENT(true),
    RATE_LIMITED(true),
    AUTHENTICATION(false),
    CONFIGURATION(false),
    INVALID_REQUEST(false),
    SCHEMA_INCOMPATIBLE(false),
    UNKNOWN(false);

    private final boolean retryable;

    ProviderFailureKind(boolean retryable) {
        this.retryable = retryable;
    }

    public boolean retryable() {
        return retryable;
    }
}
