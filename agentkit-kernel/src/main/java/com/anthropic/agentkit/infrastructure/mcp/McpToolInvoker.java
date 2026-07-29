package com.anthropic.agentkit.infrastructure.mcp;

import com.anthropic.agentkit.domain.tool.ExecutionContext;
import com.anthropic.agentkit.domain.tool.ToolArguments;

@FunctionalInterface
interface McpToolInvoker {

    McpCallResult invoke(
            McpToolDescriptor descriptor, ToolArguments arguments, ExecutionContext context);
}
