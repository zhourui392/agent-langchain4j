package com.anthropic.agentkit.infrastructure.mcp;

import com.anthropic.agentkit.domain.tool.ExecutionContext;
import com.anthropic.agentkit.domain.tool.ToolArguments;

import java.util.List;

/** One authenticated, scope-bound MCP transport session. */
public interface McpSession extends AutoCloseable {

    List<McpToolDescriptor> discoverTools();

    McpCallResult call(String toolName, ToolArguments arguments, ExecutionContext context);

    @Override
    void close();
}
