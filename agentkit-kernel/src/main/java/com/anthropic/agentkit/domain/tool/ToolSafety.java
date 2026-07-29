package com.anthropic.agentkit.domain.tool;

/** Provider-neutral safety facts available to local permission policies. */
public record ToolSafety(
        boolean readOnly,
        boolean destructive,
        boolean idempotent,
        boolean openWorld) {

    public ToolSafety {
        if (readOnly && destructive) {
            throw new IllegalArgumentException("a read-only tool cannot be destructive");
        }
    }

    public static ToolSafety readOnlyTool() {
        return new ToolSafety(true, false, true, false);
    }

    public static ToolSafety mutatingTool() {
        return new ToolSafety(false, true, false, true);
    }
}
