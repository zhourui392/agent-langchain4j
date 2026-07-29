package com.anthropic.agentkit.infrastructure.mcp;

final class McpTimeoutException extends RuntimeException {

    McpTimeoutException(String message, Throwable cause) {
        super(message, cause);
    }
}
