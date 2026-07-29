package com.anthropic.agentkit.application.recovery;

import com.anthropic.agentkit.domain.tool.ToolResult;
import com.anthropic.agentkit.domain.tool.ToolUseRequest;

import java.util.Objects;
import java.util.Optional;

/** Tool lifecycle projection rebuilt without executing the tool. */
public record RecoveredToolInvocation(
        ToolUseRequest request,
        RecoveryStatus status,
        Optional<ToolResult> result) {

    public RecoveredToolInvocation {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(status, "status");
        result = Objects.requireNonNull(result, "result");
    }
}
