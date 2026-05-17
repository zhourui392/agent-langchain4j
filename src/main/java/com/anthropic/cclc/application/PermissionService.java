package com.anthropic.cclc.application;

import com.anthropic.cclc.application.InteractivePrompter.UserPermissionResponse;
import com.anthropic.cclc.domain.permission.Decision;
import com.anthropic.cclc.domain.permission.PermissionMode;
import com.anthropic.cclc.domain.permission.PermissionPolicy;
import com.anthropic.cclc.domain.tool.Tool;
import com.anthropic.cclc.domain.tool.ToolInvocation;

import java.util.Objects;

public final class PermissionService {

    private final PermissionPolicy policy;
    private final InteractivePrompter prompter;
    private final PermissionDecisionCache cache = new PermissionDecisionCache();
    private final Object prompterLock = new Object();
    private PermissionMode mode;

    public PermissionService(PermissionPolicy policy, InteractivePrompter prompter,
                             PermissionMode initialMode) {
        this.policy = Objects.requireNonNull(policy, "policy");
        this.prompter = Objects.requireNonNull(prompter, "prompter");
        this.mode = Objects.requireNonNull(initialMode, "initialMode");
    }

    public PermissionMode mode() {
        return mode;
    }

    public void setMode(PermissionMode mode) {
        this.mode = Objects.requireNonNull(mode, "mode");
    }

    public Decision check(ToolInvocation invocation, Tool tool) {
        if (cache.allows(tool.name())) {
            return Decision.ALLOW;
        }
        Decision policyDecision = policy.decide(invocation, tool, mode);
        if (policyDecision != Decision.ASK) {
            return policyDecision;
        }
        return askInteractively(invocation, tool);
    }

    private Decision askInteractively(ToolInvocation invocation, Tool tool) {
        synchronized (prompterLock) {
            if (cache.allows(tool.name())) {
                return Decision.ALLOW;
            }
            UserPermissionResponse response = prompter.ask(invocation, tool);
            return switch (response) {
                case ALLOW_ONCE -> Decision.ALLOW;
                case ALLOW_ALWAYS -> {
                    cache.recordAllowAlways(tool.name());
                    yield Decision.ALLOW;
                }
                case DENY -> Decision.DENY;
            };
        }
    }
}
