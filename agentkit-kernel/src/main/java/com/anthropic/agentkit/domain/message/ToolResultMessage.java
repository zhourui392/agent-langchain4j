package com.anthropic.agentkit.domain.message;

import com.anthropic.agentkit.domain.tool.ToolResult;
import com.anthropic.agentkit.domain.tool.ToolResultStatus;
import com.anthropic.agentkit.domain.tool.ToolUseId;

import java.util.Map;
import java.util.Objects;

public record ToolResultMessage(
        ToolUseId toolUseId,
        ToolResultStatus status,
        String text,
        Map<String, String> metadata) implements ChatMessage {

    public ToolResultMessage {
        Objects.requireNonNull(toolUseId, "toolUseId");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(text, "text");
        metadata = Map.copyOf(Objects.requireNonNull(metadata, "metadata"));
    }

    public static ToolResultMessage of(ToolUseId toolUseId, String text) {
        return of(toolUseId, ToolResultStatus.SUCCESS, text, Map.of());
    }

    public static ToolResultMessage of(ToolUseId toolUseId, ToolResultStatus status,
                                       String text, Map<String, String> metadata) {
        return new ToolResultMessage(toolUseId, status, text, metadata);
    }

    public static ToolResultMessage from(ToolUseId toolUseId, ToolResult result) {
        Objects.requireNonNull(result, "result");
        return of(toolUseId, result.status(), result.content(), result.metadata());
    }

    public boolean isError() {
        return !status.isSuccess();
    }

    @Override
    public Role role() {
        return Role.TOOL;
    }
}
