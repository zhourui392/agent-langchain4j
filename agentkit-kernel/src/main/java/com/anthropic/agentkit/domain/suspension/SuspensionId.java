package com.anthropic.agentkit.domain.suspension;

import java.util.Objects;
import java.util.UUID;

/** Public audit identity of one durable run suspension. */
public record SuspensionId(String value) {

    public SuspensionId {
        Objects.requireNonNull(value, "value");
        if (value.isBlank()) {
            throw new IllegalArgumentException("suspension id must not be blank");
        }
    }

    public static SuspensionId fresh() {
        return new SuspensionId(UUID.randomUUID().toString());
    }

    public static SuspensionId of(String value) {
        return new SuspensionId(value);
    }

    @Override public String toString() { return value; }
}
