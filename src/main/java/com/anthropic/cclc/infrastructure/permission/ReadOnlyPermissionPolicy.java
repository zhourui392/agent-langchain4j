package com.anthropic.cclc.infrastructure.permission;

import com.anthropic.cclc.domain.permission.Decision;
import com.anthropic.cclc.domain.permission.PermissionMode;
import com.anthropic.cclc.domain.permission.PermissionPolicy;
import com.anthropic.cclc.domain.tool.Tool;
import com.anthropic.cclc.domain.tool.ToolInvocation;

/**
 * Read-only diagnosis hard constraint: allow only read-only tools, deny every
 * write tool outright. Stub for Red.
 *
 * @author zhourui(V33215020)
 * @since 2026-06-08
 */
public final class ReadOnlyPermissionPolicy implements PermissionPolicy {

    @Override
    public Decision decide(ToolInvocation invocation, Tool tool, PermissionMode mode) {
        return Decision.ASK;
    }
}
