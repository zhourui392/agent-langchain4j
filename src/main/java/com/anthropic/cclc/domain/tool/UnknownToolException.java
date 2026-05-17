package com.anthropic.cclc.domain.tool;

public final class UnknownToolException extends RuntimeException {

    public UnknownToolException(String toolName) {
        super("unknown tool: " + toolName);
    }
}
