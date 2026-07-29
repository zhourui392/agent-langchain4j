package com.anthropic.agentkit.domain.tool;

public enum InvocationState {
    PENDING,
    ALLOWED,
    SETTLED;

    public boolean isTerminal() {
        return this == SETTLED;
    }
}
