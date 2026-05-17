package com.anthropic.cclc.application;

import com.anthropic.cclc.domain.tool.Tool;
import com.anthropic.cclc.domain.tool.ToolInvocation;

public interface InteractivePrompter {

    UserPermissionResponse ask(ToolInvocation invocation, Tool tool);

    enum UserPermissionResponse {
        ALLOW_ONCE,
        ALLOW_ALWAYS,
        DENY
    }
}
