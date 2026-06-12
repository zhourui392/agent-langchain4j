package com.anthropic.cclc.domain.tool;

public enum InvocationState {
    PENDING,
    ALLOWED,
    DENIED,
    COMPLETED,
    FAILED;

    public boolean isTerminal() {
        return this == DENIED || this == COMPLETED || this == FAILED;
    }
}
