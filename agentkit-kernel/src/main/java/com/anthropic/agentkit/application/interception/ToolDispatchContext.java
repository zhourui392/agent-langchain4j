package com.anthropic.agentkit.application.interception;

import com.anthropic.agentkit.domain.tool.ExecutionContext;
import com.anthropic.agentkit.domain.tool.Tool;
import com.anthropic.agentkit.domain.tool.ToolArguments;
import com.anthropic.agentkit.domain.tool.ToolInvocation;
import com.anthropic.agentkit.domain.tool.ToolKind;
import com.anthropic.agentkit.domain.tool.ToolUseId;

import java.util.Objects;

/** Immutable policy input for one parsed tool invocation. */
public record ToolDispatchContext(
        ExecutionContext executionContext,
        ToolUseId toolUseId,
        String toolName,
        ToolArguments arguments,
        boolean readOnly,
        ToolKind kind) {

    public ToolDispatchContext {
        Objects.requireNonNull(executionContext, "executionContext");
        Objects.requireNonNull(toolUseId, "toolUseId");
        if (toolName == null || toolName.isBlank()) {
            throw new IllegalArgumentException("toolName must not be blank");
        }
        Objects.requireNonNull(arguments, "arguments");
        Objects.requireNonNull(kind, "kind");
    }

    public static ToolDispatchContext from(
            ExecutionContext context, ToolInvocation invocation, Tool tool) {
        Objects.requireNonNull(invocation, "invocation");
        Objects.requireNonNull(tool, "tool");
        return new ToolDispatchContext(
                context, invocation.id(), invocation.toolName(), invocation.args(),
                tool.isReadOnly(), tool.kind());
    }
}
