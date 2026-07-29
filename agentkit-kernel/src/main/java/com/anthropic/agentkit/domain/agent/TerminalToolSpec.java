package com.anthropic.agentkit.domain.agent;

import java.util.Objects;

/** Provider-neutral schema of the structured tool that terminates an agent role. */
public record TerminalToolSpec(String name, String description, String schema) {

    public TerminalToolSpec {
        requireText(name, "name");
        requireText(description, "description");
        requireText(schema, "schema");
    }

    private static void requireText(String value, String field) {
        Objects.requireNonNull(value, field);
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
