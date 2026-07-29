package com.anthropic.agentkit.application.interception;

/** Why context governance is about to evaluate compaction. */
public enum CompactionCause {
    BEFORE_LLM_CALL,
    CONTEXT_OVERFLOW_RECOVERY
}
