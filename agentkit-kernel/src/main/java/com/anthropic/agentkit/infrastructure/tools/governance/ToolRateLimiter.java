package com.anthropic.agentkit.infrastructure.tools.governance;

import com.anthropic.agentkit.domain.tool.ExecutionContext;

/**
 * Run-aware admission control applied before a governed tool reaches its backend.
 *
 * @author alex
 */
@FunctionalInterface
public interface ToolRateLimiter {

    ToolRateLimiter UNLIMITED = (toolName, context) -> true;

    boolean tryAcquire(String toolName, ExecutionContext context);
}
