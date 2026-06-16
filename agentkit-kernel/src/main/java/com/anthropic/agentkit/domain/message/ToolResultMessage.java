package com.anthropic.agentkit.domain.message;

import com.anthropic.agentkit.domain.tool.ToolUseId;

import java.util.Objects;

public record ToolResultMessage(ToolUseId toolUseId, String text) implements ChatMessage {
    public ToolResultMessage {
        Objects.requireNonNull(toolUseId, "toolUseId");
        Objects.requireNonNull(text, "text");
    }

    public static ToolResultMessage of(ToolUseId toolUseId, String text) {
        return new ToolResultMessage(toolUseId, text);
    }

    @Override
    public Role role() {
        return Role.TOOL;
    }
}
