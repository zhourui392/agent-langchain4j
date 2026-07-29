package com.anthropic.agentkit.domain.tool;

import java.time.Instant;
import java.util.Objects;

public final class ToolInvocation {

    private final ToolUseId id;
    private final String toolName;
    private final ToolArguments args;
    private final Instant requestedAt;

    private InvocationState state = InvocationState.PENDING;
    private ToolResult result;

    private ToolInvocation(ToolUseId id, String toolName, ToolArguments args, Instant requestedAt) {
        this.id = Objects.requireNonNull(id, "id");
        this.toolName = Objects.requireNonNull(toolName, "toolName");
        this.args = Objects.requireNonNull(args, "args");
        this.requestedAt = Objects.requireNonNull(requestedAt, "requestedAt");
    }

    public static ToolInvocation create(ToolUseId id, String toolName, ToolArguments args) {
        return new ToolInvocation(id, toolName, args, Instant.now());
    }

    public ToolUseId id() { return id; }
    public String toolName() { return toolName; }
    public ToolArguments args() { return args; }
    public Instant requestedAt() { return requestedAt; }
    public InvocationState state() { return state; }
    public ToolResult result() { return result; }

    public void allow() {
        requireState(InvocationState.PENDING, "allow");
        state = InvocationState.ALLOWED;
    }

    public void settle(ToolResult outcome) {
        Objects.requireNonNull(outcome, "outcome");
        InvocationState expected = outcome.status().canSettleWithoutPermission()
                ? InvocationState.PENDING
                : InvocationState.ALLOWED;
        requireState(expected, "settle as " + outcome.status());
        result = outcome;
        state = InvocationState.SETTLED;
    }

    private void requireState(InvocationState expected, String action) {
        if (state != expected) {
            throw new IllegalStateException(
                    "cannot " + action + " from " + state + "; expected " + expected);
        }
    }
}
