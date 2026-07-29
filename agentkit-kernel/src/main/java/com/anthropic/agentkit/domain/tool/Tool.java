package com.anthropic.agentkit.domain.tool;

public interface Tool {

    String name();

    String description();

    String inputSchema();

    boolean isReadOnly();

    default ToolKind kind() {
        return ToolKind.STANDARD;
    }

    ToolResult execute(ToolArguments args, ExecutionContext ctx);
}
