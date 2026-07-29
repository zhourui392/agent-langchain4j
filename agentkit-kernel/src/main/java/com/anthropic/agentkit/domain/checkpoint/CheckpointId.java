package com.anthropic.agentkit.domain.checkpoint;

import java.util.Objects;
import java.util.UUID;

/** Opaque identity of one immutable pre-write file snapshot. */
public record CheckpointId(String value) {

    public CheckpointId {
        Objects.requireNonNull(value, "value");
        if (value.isBlank()) {
            throw new IllegalArgumentException("checkpoint id must not be blank");
        }
    }

    public static CheckpointId of(String value) {
        return new CheckpointId(value);
    }

    public static CheckpointId fresh() {
        return new CheckpointId(UUID.randomUUID().toString());
    }

    @Override
    public String toString() {
        return value;
    }
}
