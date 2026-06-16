package com.anthropic.agentkit.infrastructure.permission;

import com.anthropic.agentkit.domain.permission.Decision;
import com.anthropic.agentkit.domain.permission.PermissionMode;
import com.anthropic.agentkit.domain.permission.PermissionPolicy;
import com.anthropic.agentkit.domain.tool.Tool;
import com.anthropic.agentkit.domain.tool.ToolInvocation;

/**
 * Read-only execution hard constraint.
 *
 * <p>A read-only run never mutates the systems it inspects, so this policy
 * collapses the usual ALLOW/ASK/DENY ladder: a read-only tool is allowed, any
 * write tool is denied outright. There is no interactive approval path (no ASK)
 * and the {@link PermissionMode} is irrelevant, even {@code BYPASS} cannot
 * unlock a write tool. Bash is non-read-only by nature, so it is denied here.
 *
 * @author zhourui(V33215020)
 * @since 2026-06-08
 */
public final class ReadOnlyPermissionPolicy implements PermissionPolicy {

    @Override
    public Decision decide(ToolInvocation invocation, Tool tool, PermissionMode mode) {
        return tool.isReadOnly() ? Decision.ALLOW : Decision.DENY;
    }
}
