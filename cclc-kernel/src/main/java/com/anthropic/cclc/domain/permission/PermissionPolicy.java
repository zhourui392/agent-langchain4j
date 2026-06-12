package com.anthropic.cclc.domain.permission;

import com.anthropic.cclc.domain.tool.Tool;
import com.anthropic.cclc.domain.tool.ToolInvocation;

public interface PermissionPolicy {

    Decision decide(ToolInvocation invocation, Tool tool, PermissionMode mode);
}
