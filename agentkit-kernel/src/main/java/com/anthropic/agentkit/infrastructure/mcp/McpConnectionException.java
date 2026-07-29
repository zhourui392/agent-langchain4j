package com.anthropic.agentkit.infrastructure.mcp;

/** Transport/session failure whose reconnect applies only to a later invocation. */
public final class McpConnectionException extends RuntimeException {

    public McpConnectionException(String message) {
        super(message);
    }

    public McpConnectionException(String message, Throwable cause) {
        super(message, cause);
    }
}
