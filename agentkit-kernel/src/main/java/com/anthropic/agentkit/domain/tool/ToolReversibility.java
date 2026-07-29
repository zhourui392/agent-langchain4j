package com.anthropic.agentkit.domain.tool;

/** Provider-neutral compensation contract for a tool's external effects. */
public enum ToolReversibility {
    NONE,
    CHECKPOINTED_FILE,
    NON_REVERSIBLE
}
