package com.anthropic.cclc.infrastructure.permission;

import com.anthropic.cclc.domain.permission.Decision;
import com.anthropic.cclc.domain.permission.PermissionMode;
import com.anthropic.cclc.domain.permission.PermissionPolicy;
import com.anthropic.cclc.domain.tool.Tool;
import com.anthropic.cclc.domain.tool.ToolInvocation;

/**
 * Read-only diagnosis hard constraint.
 *
 * <p>The diagnose engine never mutates the systems it inspects, so this policy
 * collapses the usual ALLOW/ASK/DENY ladder: a read-only tool is allowed, any
 * write tool is denied outright. There is no interactive approval path (no ASK)
 * and the {@link PermissionMode} is irrelevant — even {@code BYPASS} cannot
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
