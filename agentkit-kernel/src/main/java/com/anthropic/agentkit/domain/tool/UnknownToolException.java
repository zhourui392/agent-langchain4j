package com.anthropic.agentkit.domain.tool;

public final class UnknownToolException extends RuntimeException {

    public UnknownToolException(String toolName) {
        super("unknown tool: " + toolName);
    }
}
