package com.anthropic.agentkit.application;

import com.anthropic.agentkit.domain.permission.Decision;
import com.anthropic.agentkit.domain.suspension.ApprovalRequest;
import com.anthropic.agentkit.domain.suspension.PlannedToolInvocation;

import java.util.List;
import java.util.Objects;

/** Immutable decision snapshot for a complete assistant tool batch. */
record ToolPermissionPlan(List<PlannedToolInvocation> invocations) {

    ToolPermissionPlan {
        invocations = List.copyOf(Objects.requireNonNull(invocations, "invocations"));
        if (invocations.isEmpty()) {
            throw new IllegalArgumentException("tool permission plan must not be empty");
        }
    }

    boolean requiresApproval() {
        return invocations.stream().anyMatch(item -> item.decision() == Decision.ASK);
    }

    ApprovalRequest approvalRequest() {
        if (!requiresApproval()) {
            throw new IllegalStateException("permission plan does not require approval");
        }
        return new ApprovalRequest(invocations);
    }
}
