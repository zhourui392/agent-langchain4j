package com.anthropic.agentkit.application;

import com.anthropic.agentkit.domain.tool.Tool;
import com.anthropic.agentkit.domain.tool.ToolInvocation;

public interface InteractivePrompter {

    UserPermissionResponse ask(ToolInvocation invocation, Tool tool);

    enum UserPermissionResponse {
        ALLOW_ONCE,
        ALLOW_ALWAYS,
        DENY
    }
}
