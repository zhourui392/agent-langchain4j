package com.anthropic.agentkit.application.tool;

import com.anthropic.agentkit.domain.tool.ExecutionContext;
import com.anthropic.agentkit.domain.tool.ToolInvocation;
import com.anthropic.agentkit.domain.tool.ToolResult;

/** Mandatory post-execution governance for every registered tool result. */
@FunctionalInterface
public interface ToolOutputPolicy {

    ToolResult govern(ToolInvocation invocation, ToolResult raw, ExecutionContext context);

    static ToolOutputPolicy defaultLimited() {
        return LimitedToolOutputPolicy.defaults();
    }
}
