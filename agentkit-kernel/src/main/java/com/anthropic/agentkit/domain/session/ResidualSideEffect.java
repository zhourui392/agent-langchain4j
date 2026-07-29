package com.anthropic.agentkit.domain.session;

import com.anthropic.agentkit.domain.tool.ToolUseId;

import java.util.Objects;

/** External effect that remains after conversation/file rewind. */
public record ResidualSideEffect(
        RunEventPointer event,
        ToolUseId toolUseId,
        String toolName,
        String detail) {

    public ResidualSideEffect {
        Objects.requireNonNull(event, "event");
        Objects.requireNonNull(toolUseId, "toolUseId");
        if (toolName == null || toolName.isBlank()) {
            throw new IllegalArgumentException("toolName must not be blank");
        }
        if (detail == null || detail.isBlank()) {
            throw new IllegalArgumentException("detail must not be blank");
        }
    }
}
