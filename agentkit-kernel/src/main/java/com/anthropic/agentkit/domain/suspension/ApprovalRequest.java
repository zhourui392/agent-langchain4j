package com.anthropic.agentkit.domain.suspension;

import com.anthropic.agentkit.domain.permission.Decision;
import com.anthropic.agentkit.domain.tool.ToolUseId;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Complete, ordered permission plan for one suspended tool batch. */
public record ApprovalRequest(List<PlannedToolInvocation> invocations) {

    public ApprovalRequest {
        invocations = List.copyOf(Objects.requireNonNull(invocations, "invocations"));
        if (invocations.isEmpty() || invocations.stream()
                .noneMatch(item -> item.decision() == Decision.ASK)) {
            throw new IllegalArgumentException("approval request must contain an ASK decision");
        }
        Set<ToolUseId> ids = new HashSet<>();
        if (invocations.stream().anyMatch(item -> !ids.add(item.request().id()))) {
            throw new IllegalArgumentException("approval request contains duplicate tool use ids");
        }
    }
}
