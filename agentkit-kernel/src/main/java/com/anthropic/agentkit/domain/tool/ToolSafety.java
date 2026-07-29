package com.anthropic.agentkit.domain.tool;

/** Provider-neutral safety facts available to local permission policies. */
public record ToolSafety(
        boolean readOnly,
        boolean destructive,
        boolean idempotent,
        boolean openWorld,
        ToolReversibility reversibility) {

    public ToolSafety {
        if (reversibility == null) {
            throw new NullPointerException("reversibility");
        }
        if (readOnly && destructive) {
            throw new IllegalArgumentException("a read-only tool cannot be destructive");
        }
        if (readOnly && reversibility != ToolReversibility.NONE) {
            throw new IllegalArgumentException(
                    "a read-only tool cannot declare a side effect");
        }
    }

    public ToolSafety(
            boolean readOnly, boolean destructive,
            boolean idempotent, boolean openWorld) {
        this(readOnly, destructive, idempotent, openWorld,
                readOnly ? ToolReversibility.NONE : ToolReversibility.NON_REVERSIBLE);
    }

    public static ToolSafety readOnlyTool() {
        return new ToolSafety(
                true, false, true, false, ToolReversibility.NONE);
    }

    public static ToolSafety mutatingTool() {
        return new ToolSafety(
                false, true, false, true, ToolReversibility.NON_REVERSIBLE);
    }

    public static ToolSafety checkpointedFileMutation() {
        return new ToolSafety(
                false, true, false, false, ToolReversibility.CHECKPOINTED_FILE);
    }
}
