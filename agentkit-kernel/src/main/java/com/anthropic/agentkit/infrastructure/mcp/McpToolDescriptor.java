package com.anthropic.agentkit.infrastructure.mcp;

import java.util.Objects;

/** Validatable declaration returned by one remote MCP server. */
public record McpToolDescriptor(
        String name,
        String description,
        String inputSchema,
        McpToolAnnotations annotations) {

    public McpToolDescriptor {
        requireText(name, "tool name");
        description = description == null ? "" : description;
        requireText(inputSchema, "input schema");
        Objects.requireNonNull(annotations, "annotations");
    }

    private static void requireText(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
    }
}
