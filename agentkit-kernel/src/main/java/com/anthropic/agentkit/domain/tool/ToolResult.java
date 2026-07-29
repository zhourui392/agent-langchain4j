package com.anthropic.agentkit.domain.tool;

import java.util.Map;
import java.util.Objects;

public record ToolResult(ToolResultStatus status, String content, Map<String, String> metadata) {

    public ToolResult {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(content, "content");
        metadata = Map.copyOf(Objects.requireNonNull(metadata, "metadata"));
    }

    public ToolResult(boolean success, String content) {
        this(success ? ToolResultStatus.SUCCESS : ToolResultStatus.ERROR, content, Map.of());
    }

    public boolean success() {
        return status.isSuccess();
    }

    public static ToolResult ok(String content) {
        return of(ToolResultStatus.SUCCESS, content);
    }

    public static ToolResult error(String content) {
        return of(ToolResultStatus.ERROR, content);
    }

    public static ToolResult of(ToolResultStatus status, String content) {
        return new ToolResult(status, content, Map.of());
    }

    public static ToolResult of(ToolResultStatus status, String content,
                                Map<String, String> metadata) {
        return new ToolResult(status, content, metadata);
    }

    public ToolResult withContent(String replacement) {
        return new ToolResult(status, replacement, metadata);
    }
}
