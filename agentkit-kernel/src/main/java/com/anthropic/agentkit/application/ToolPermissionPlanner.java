package com.anthropic.agentkit.application;

import com.anthropic.agentkit.domain.message.AiMessage;
import com.anthropic.agentkit.domain.permission.Decision;
import com.anthropic.agentkit.domain.suspension.ApprovalRequest;
import com.anthropic.agentkit.domain.suspension.PlannedToolInvocation;
import com.anthropic.agentkit.domain.tool.ExecutionContext;
import com.anthropic.agentkit.domain.tool.Tool;
import com.anthropic.agentkit.domain.tool.ToolInvocation;
import com.anthropic.agentkit.domain.tool.ToolRegistry;
import com.anthropic.agentkit.domain.tool.ToolUseRequest;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Computes a complete permission batch before any invocation can start. */
final class ToolPermissionPlanner {

    private final ToolRegistry tools;
    private final PermissionService permissions;

    ToolPermissionPlanner(ToolRegistry tools, PermissionService permissions) {
        this.tools = Objects.requireNonNull(tools, "tools");
        this.permissions = Objects.requireNonNull(permissions, "permissions");
    }

    Optional<ApprovalRequest> plan(
            AiMessage message, ExecutionContext context) {
        List<PlannedToolInvocation> plan = new ArrayList<>();
        boolean asks = false;
        for (ToolUseRequest request : message.toolUseRequests()) {
            Decision decision = decisionFor(request, context);
            plan.add(new PlannedToolInvocation(request, decision));
            asks |= decision == Decision.ASK;
        }
        return asks ? Optional.of(new ApprovalRequest(plan)) : Optional.empty();
    }

    private Decision decisionFor(
            ToolUseRequest request, ExecutionContext context) {
        try {
            Tool tool = tools.find(request.toolName(), context);
            ToolInvocation invocation = InvocationFactory.from(request);
            return permissions.decide(context, invocation, tool);
        } catch (RuntimeException failure) {
            return Decision.DENY;
        }
    }
}
