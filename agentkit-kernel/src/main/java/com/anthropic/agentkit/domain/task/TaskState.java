package com.anthropic.agentkit.domain.task;

import com.anthropic.agentkit.domain.tool.ToolResultStatus;

/** Monotonic lifecycle state of one background task. */
public enum TaskState {
    STARTING,
    RUNNING,
    COMPLETED,
    FAILED,
    CANCELLED,
    TIMED_OUT;

    public boolean terminal() {
        return switch (this) {
            case COMPLETED, FAILED, CANCELLED, TIMED_OUT -> true;
            case STARTING, RUNNING -> false;
        };
    }

    public static TaskState from(ToolResultStatus status) {
        return switch (status) {
            case SUCCESS -> COMPLETED;
            case CANCELLED -> CANCELLED;
            case TIMEOUT -> TIMED_OUT;
            default -> FAILED;
        };
    }
}
