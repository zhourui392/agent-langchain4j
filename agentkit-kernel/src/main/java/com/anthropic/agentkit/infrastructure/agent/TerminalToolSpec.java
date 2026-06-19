package com.anthropic.agentkit.infrastructure.agent;

import java.util.Objects;

/**
 * Specification of a terminal (structured-output) tool — the only sanctioned way
 * a structured agent role finishes its turn. Materializes the role's "exit channel"
 * as a value object: name, description, and JSON schema for the payload.
 *
 * @author zhourui(V33215020)
 * @since 2026-06-19
 */
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
