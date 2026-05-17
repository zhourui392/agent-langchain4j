package com.anthropic.cclc.infrastructure.permission;

import com.anthropic.cclc.domain.permission.Decision;
import com.anthropic.cclc.domain.permission.PermissionMode;
import com.anthropic.cclc.domain.permission.PermissionPolicy;
import com.anthropic.cclc.domain.tool.Tool;
import com.anthropic.cclc.domain.tool.ToolInvocation;

import java.util.Set;

public final class DefaultPermissionPolicy implements PermissionPolicy {

    private static final Set<String> AUTO_MODE_SAFELIST = Set.of(
            "Read", "Glob", "Grep");

    private final Set<String> autoSafelist;

    public DefaultPermissionPolicy() {
        this(AUTO_MODE_SAFELIST);
    }

    public DefaultPermissionPolicy(Set<String> autoSafelist) {
        this.autoSafelist = Set.copyOf(autoSafelist);
    }

    @Override
    public Decision decide(ToolInvocation invocation, Tool tool, PermissionMode mode) {
        return switch (mode) {
            case BYPASS -> Decision.ALLOW;
            case DEFAULT -> tool.isReadOnly() ? Decision.ALLOW : Decision.ASK;
            case PLAN -> tool.isReadOnly() ? Decision.ALLOW : Decision.DENY;
            case AUTO -> tool.isReadOnly() || autoSafelist.contains(tool.name())
                    ? Decision.ALLOW
                    : Decision.ASK;
        };
    }
}
