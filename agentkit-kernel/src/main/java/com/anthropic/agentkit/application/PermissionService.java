package com.anthropic.agentkit.application;

import com.anthropic.agentkit.application.InteractivePrompter.UserPermissionResponse;
import com.anthropic.agentkit.domain.agent.RunId;
import com.anthropic.agentkit.domain.permission.Decision;
import com.anthropic.agentkit.domain.permission.PermissionMode;
import com.anthropic.agentkit.domain.permission.PermissionPolicy;
import com.anthropic.agentkit.domain.tool.ExecutionContext;
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
    private final PermissionMode mode;

    public PermissionService(PermissionPolicy policy, InteractivePrompter prompter,
                             PermissionMode initialMode) {
        this.policy = Objects.requireNonNull(policy, "policy");
        this.prompter = Objects.requireNonNull(prompter, "prompter");
        this.mode = Objects.requireNonNull(initialMode, "initialMode");
    }

    public PermissionMode mode() {
        return mode;
    }

    public static PermissionService bypassing() {
        return new PermissionService(
                (invocation, tool, mode) -> Decision.ALLOW,
                (invocation, tool) -> {
                    throw new IllegalStateException("interactive permission prompt is disabled");
                },
                PermissionMode.BYPASS);
    }

    public Decision check(ExecutionContext context, ToolInvocation invocation, Tool tool) {
        Decision planned = decide(context, invocation, tool);
        if (planned != Decision.ASK) {
            return planned;
        }
        Decision interactiveDecision = askInteractively(context, invocation, tool);
        logDecision(context, tool, interactiveDecision);
        return interactiveDecision;
    }

    /** Evaluates policy and cache without invoking a synchronous host prompt. */
    public Decision decide(ExecutionContext context, ToolInvocation invocation, Tool tool) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(invocation, "invocation");
        Objects.requireNonNull(tool, "tool");
        Decision policyDecision = policy.decide(invocation, tool, mode);
        if (policyDecision != Decision.ASK) {
            logDecision(context, tool, policyDecision);
            return policyDecision;
        }
        if (cache.allows(context, invocation)) {
            log.debug("permission cache hit: run={}, workspace={}, tool={}",
                    context.runId(), context.workspaceId(), tool.name());
            logDecision(context, tool, Decision.ALLOW);
            return Decision.ALLOW;
        }
        logDecision(context, tool, Decision.ASK);
        return Decision.ASK;
    }

    public void clear(RunId runId) {
        cache.clear(Objects.requireNonNull(runId, "runId"));
    }

    private Decision askInteractively(
            ExecutionContext context, ToolInvocation invocation, Tool tool) {
        synchronized (prompterLock) {
            if (cache.allows(context, invocation)) {
                log.debug("permission cache hit during prompt lock: run={}, workspace={}, tool={}",
                        context.runId(), context.workspaceId(), tool.name());
                return Decision.ALLOW;
            }
            log.warn("permission prompt shown: run={}, workspace={}, tool={}",
                    context.runId(), context.workspaceId(), tool.name());
            UserPermissionResponse response = prompter.ask(invocation, tool);
            log.warn("permission prompt answered: run={}, workspace={}, tool={}, response={}",
                    context.runId(), context.workspaceId(), tool.name(), response);
            return switch (response) {
                case ALLOW_ONCE -> Decision.ALLOW;
                case ALLOW_ALWAYS -> {
                    cache.recordAllowAlways(context, invocation);
                    yield Decision.ALLOW;
                }
                case DENY -> Decision.DENY;
            };
        }
    }

    private static void logDecision(
            ExecutionContext context, Tool tool, Decision decision) {
        log.info("permission check: run={}, workspace={}, tool={}, decision={}",
                context.runId(), context.workspaceId(), tool.name(), decision);
    }
}
