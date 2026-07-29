package com.anthropic.agentkit.infrastructure.mcp;

final class McpCancelledException extends RuntimeException {

    McpCancelledException(String message) {
        super(message);
    }

    McpCancelledException(String message, Throwable cause) {
        super(message, cause);
    }
}
