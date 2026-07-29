package com.anthropic.agentkit.domain.suspension;

import com.anthropic.agentkit.domain.permission.Decision;
import com.anthropic.agentkit.domain.tool.ToolUseRequest;

import java.util.Objects;

/** Permission fact captured before any tool in a batch starts. */
public record PlannedToolInvocation(ToolUseRequest request, Decision decision) {

    public PlannedToolInvocation {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(decision, "decision");
    }
}
