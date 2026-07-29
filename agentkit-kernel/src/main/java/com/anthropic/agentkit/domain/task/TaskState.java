package com.anthropic.agentkit.domain.task;

import com.anthropic.agentkit.domain.tool.ToolResultStatus;

import java.util.Objects;

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

    /** Validates the monotonic lifecycle transition and returns the target state. */
    public TaskState transitionTo(TaskState target) {
        Objects.requireNonNull(target, "target");
        if (this == target || allowedTarget(target)) {
            return target;
        }
        throw new IllegalStateException(
                "invalid background task transition: " + this + " -> " + target);
    }

    private boolean allowedTarget(TaskState target) {
        return switch (this) {
            case STARTING -> target == RUNNING || target.terminal();
            case RUNNING -> target.terminal();
            case COMPLETED, FAILED, CANCELLED, TIMED_OUT -> false;
        };
    }

    public static TaskState from(ToolResultStatus status) {
        Objects.requireNonNull(status, "status");
        return switch (status) {
            case SUCCESS -> COMPLETED;
            case CANCELLED -> CANCELLED;
            case TIMEOUT -> TIMED_OUT;
            default -> FAILED;
        };
    }
}
