package com.anthropic.cclc.domain.tool;

import java.util.Objects;

public record ToolUseRequest(ToolUseId id, String toolName, String argumentsJson) {
    public ToolUseRequest {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(toolName, "toolName");
        Objects.requireNonNull(argumentsJson, "argumentsJson");
        if (toolName.isBlank()) {
            throw new IllegalArgumentException("toolName must not be blank");
        }
    }
}
