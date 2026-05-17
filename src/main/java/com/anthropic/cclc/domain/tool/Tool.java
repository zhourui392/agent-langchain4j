package com.anthropic.cclc.domain.tool;

public interface Tool {

    String name();

    String description();

    String inputSchema();

    boolean isReadOnly();

    ToolResult execute(ToolArguments args, ExecutionContext ctx);
}
