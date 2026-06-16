package com.anthropic.agentkit.domain.tool;

public interface Tool {

    String name();

    String description();

    String inputSchema();

    boolean isReadOnly();

    ToolResult execute(ToolArguments args, ExecutionContext ctx);
}
