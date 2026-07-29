package com.anthropic.agentkit.infrastructure.mcp;

import java.util.Map;
import java.util.Objects;

/** Text projection of a validated MCP tools/call result. */
public record McpCallResult(boolean error, String content, Map<String, String> metadata) {

    public McpCallResult {
        Objects.requireNonNull(content, "content");
        metadata = Map.copyOf(Objects.requireNonNull(metadata, "metadata"));
    }

    public static McpCallResult success(String content) {
        return new McpCallResult(false, content, Map.of());
    }

    public static McpCallResult error(String content) {
        return new McpCallResult(true, content, Map.of());
    }
}
