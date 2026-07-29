package com.anthropic.agentkit.infrastructure.mcp;

/** Conservative local projection of MCP tool safety hints. */
public record McpToolAnnotations(
        boolean readOnlyHint,
        boolean destructiveHint,
        boolean idempotentHint,
        boolean openWorldHint) {

    public static McpToolAnnotations readOnly() {
        return new McpToolAnnotations(true, false, true, false);
    }

    public static McpToolAnnotations destructive() {
        return new McpToolAnnotations(false, true, false, true);
    }

    public static McpToolAnnotations conservative() {
        return destructive();
    }

    public boolean permitsReadOnlyClassification() {
        return readOnlyHint && !destructiveHint;
    }
}
