package com.anthropic.agentkit.infrastructure.mcp;

/** Invalid MCP schema or JSON-RPC result. */
public final class McpProtocolException extends RuntimeException {

    public McpProtocolException(String message) {
        super(message);
    }

    public McpProtocolException(String message, Throwable cause) {
        super(message, cause);
    }
}
