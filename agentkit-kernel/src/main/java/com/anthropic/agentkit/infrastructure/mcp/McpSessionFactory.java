package com.anthropic.agentkit.infrastructure.mcp;

import com.anthropic.agentkit.domain.tool.ExecutionContext;

/** Opens a transport using only credentials resolved from the supplied scope. */
@FunctionalInterface
public interface McpSessionFactory {

    McpSession open(McpServerConfig config, ExecutionContext context);
}
