package com.anthropic.agentkit.domain.agent;

import java.util.Objects;
import java.util.UUID;

/** Identifies exactly one execution of an agent loop. */
public record RunId(String value) {

    public RunId {
        Objects.requireNonNull(value, "value");
        if (value.isBlank()) {
            throw new IllegalArgumentException("run id must not be blank");
        }
    }

    public static RunId of(String value) {
        return new RunId(value);
    }

    public static RunId fresh() {
        return new RunId(UUID.randomUUID().toString());
    }

    @Override
    public String toString() {
        return value;
    }
}
