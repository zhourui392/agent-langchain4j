package com.anthropic.agentkit.domain.suspension;

import java.util.Map;
import java.util.Objects;

/** Domain-neutral question envelope understood by CLI and Web hosts. */
public record InputRequest(String prompt, Map<String, String> metadata) {

    public InputRequest {
        Objects.requireNonNull(prompt, "prompt");
        if (prompt.isBlank()) {
            throw new IllegalArgumentException("input prompt must not be blank");
        }
        metadata = Map.copyOf(Objects.requireNonNull(metadata, "metadata"));
    }

    public static InputRequest of(String prompt) {
        return new InputRequest(prompt, Map.of());
    }
}
