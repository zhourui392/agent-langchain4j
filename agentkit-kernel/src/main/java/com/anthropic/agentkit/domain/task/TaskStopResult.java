package com.anthropic.agentkit.domain.task;

import java.util.Objects;

/** Result of a stop request, including the task's actual post-request state. */
public record TaskStopResult(TaskSnapshot snapshot, boolean changed) {

    public TaskStopResult {
        Objects.requireNonNull(snapshot, "snapshot");
    }
}
