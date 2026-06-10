package com.anthropic.cclc.domain.tool;

import java.util.Objects;

public record ToolUseId(String value) {
    public ToolUseId {
        Objects.requireNonNull(value, "tool use id");
        if (value.isBlank()) {
            throw new IllegalArgumentException("tool use id must not be blank");
        }
    }

    @Override
    public String toString() {
        return value;
    }
}
