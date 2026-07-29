package com.anthropic.agentkit.domain.task;

import java.util.Objects;
import java.util.UUID;

/** Opaque identity of one background task. */
public record TaskId(String value) {

    public TaskId {
        Objects.requireNonNull(value, "value");
        if (value.isBlank()) {
            throw new IllegalArgumentException("task id must not be blank");
        }
    }

    public static TaskId of(String value) {
        return new TaskId(value);
    }

    public static TaskId fresh() {
        return new TaskId(UUID.randomUUID().toString());
    }

    @Override public String toString() { return value; }
}
