package com.anthropic.agentkit.domain.permission;

import com.anthropic.agentkit.domain.tool.Tool;
import com.anthropic.agentkit.domain.tool.ToolInvocation;

public interface PermissionPolicy {

    Decision decide(ToolInvocation invocation, Tool tool, PermissionMode mode);
}
