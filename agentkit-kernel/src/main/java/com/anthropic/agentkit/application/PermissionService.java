package com.anthropic.agentkit.application;

import com.anthropic.agentkit.application.InteractivePrompter.UserPermissionResponse;
import com.anthropic.agentkit.domain.permission.Decision;
import com.anthropic.agentkit.domain.permission.PermissionMode;
import com.anthropic.agentkit.domain.permission.PermissionPolicy;
import com.anthropic.agentkit.domain.tool.Tool;
import com.anthropic.agentkit.domain.tool.ToolInvocation;

import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class PermissionService {

    private static final Logger log = LoggerFactory.getLogger(PermissionService.class);

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
            log.debug("permission cache hit: tool={}, decision={}", tool.name(), Decision.ALLOW);
            log.info("permission check: tool={}, decision={}", tool.name(), Decision.ALLOW);
            return Decision.ALLOW;
        }
        Decision policyDecision = policy.decide(invocation, tool, mode);
        if (policyDecision != Decision.ASK) {
            log.info("permission check: tool={}, decision={}", tool.name(), policyDecision);
            return policyDecision;
        }
        Decision interactiveDecision = askInteractively(invocation, tool);
        log.info("permission check: tool={}, decision={}", tool.name(), interactiveDecision);
        return interactiveDecision;
    }

    private Decision askInteractively(ToolInvocation invocation, Tool tool) {
        synchronized (prompterLock) {
            if (cache.allows(tool.name())) {
                log.debug("permission cache hit during prompt lock: tool={}", tool.name());
                return Decision.ALLOW;
            }
            log.warn("permission prompt shown: tool={}", tool.name());
            UserPermissionResponse response = prompter.ask(invocation, tool);
            log.warn("permission prompt answered: tool={}, response={}", tool.name(), response);
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
