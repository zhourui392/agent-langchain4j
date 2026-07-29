package com.anthropic.agentkit.application.interception;

import com.anthropic.agentkit.domain.tool.ToolResult;

import java.util.Objects;

/** Observed, already-settled result of one parsed tool invocation. */
public record ToolSettled(ToolDispatchContext dispatch, ToolResult result) {

    public ToolSettled {
        Objects.requireNonNull(dispatch, "dispatch");
        Objects.requireNonNull(result, "result");
    }
}
