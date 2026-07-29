package com.anthropic.agentkit.infrastructure.permission;

import com.anthropic.agentkit.domain.permission.Decision;
import com.anthropic.agentkit.domain.permission.PermissionMode;
import com.anthropic.agentkit.domain.permission.PermissionPolicy;
import com.anthropic.agentkit.domain.tool.Tool;
import com.anthropic.agentkit.domain.tool.ToolInvocation;

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
            case DEFAULT -> tool.safety().readOnly() ? Decision.ALLOW : Decision.ASK;
            case PLAN -> tool.safety().readOnly() ? Decision.ALLOW : Decision.DENY;
            case AUTO -> tool.safety().readOnly() || autoSafelist.contains(tool.name())
                    ? Decision.ALLOW
                    : Decision.ASK;
        };
    }
}
