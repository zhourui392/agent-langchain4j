package com.anthropic.agentkit.domain.agent;

import java.util.Objects;

/** Logical configuration key required before an agent can be invoked. */
public record ConfigKey(String value) {

    public ConfigKey {
        Objects.requireNonNull(value, "value");
        if (value.isBlank()) {
            throw new IllegalArgumentException("config key must not be blank");
        }
    }

    public static ConfigKey of(String value) {
        return new ConfigKey(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
