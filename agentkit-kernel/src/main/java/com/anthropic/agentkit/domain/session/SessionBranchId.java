package com.anthropic.agentkit.domain.session;

import java.util.Objects;
import java.util.UUID;

/** Stable identity of one append-only session branch. */
public record SessionBranchId(String value) {

    public SessionBranchId {
        Objects.requireNonNull(value, "value");
        if (value.isBlank()) {
            throw new IllegalArgumentException("session branch id must not be blank");
        }
    }

    public static SessionBranchId of(String value) {
        return new SessionBranchId(value);
    }

    public static SessionBranchId fresh() {
        return new SessionBranchId(UUID.randomUUID().toString());
    }

    @Override
    public String toString() {
        return value;
    }
}
