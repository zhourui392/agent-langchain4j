package com.anthropic.cclc.domain.tool;

import java.util.Objects;

public record ToolResult(boolean success, String content) {

    public ToolResult {
        Objects.requireNonNull(content, "content");
    }

    public static ToolResult ok(String content) {
        return new ToolResult(true, content);
    }

    public static ToolResult error(String content) {
        return new ToolResult(false, content);
    }
}
