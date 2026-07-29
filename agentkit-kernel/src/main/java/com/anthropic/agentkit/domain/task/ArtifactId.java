package com.anthropic.agentkit.domain.task;

import java.util.Objects;
import java.util.UUID;

/** Opaque identity of one persisted task-output artifact. */
public record ArtifactId(String value) {

    public ArtifactId {
        Objects.requireNonNull(value, "value");
        if (value.isBlank()) {
            throw new IllegalArgumentException("artifact id must not be blank");
        }
    }

    public static ArtifactId fresh() {
        return new ArtifactId(UUID.randomUUID().toString());
    }

    @Override public String toString() { return value; }
}
