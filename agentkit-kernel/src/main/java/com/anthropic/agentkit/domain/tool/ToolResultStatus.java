package com.anthropic.agentkit.domain.tool;

/** Protocol-level terminal outcome of one tool-use request. */
public enum ToolResultStatus {
    SUCCESS,
    ERROR,
    DENIED,
    CANCELLED,
    TIMEOUT,
    UNKNOWN_TOOL,
    INVALID_ARGUMENTS,
    BUDGET_EXHAUSTED;

    public boolean isSuccess() {
        return this == SUCCESS;
    }

    public boolean isTerminal() {
        return true;
    }
}
