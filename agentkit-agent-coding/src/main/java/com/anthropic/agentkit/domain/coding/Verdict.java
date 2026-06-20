package com.anthropic.agentkit.domain.coding;

/**
 * Reviewer's terminal decision on a {@link Patch}.
 */
public enum Verdict {
    /** Patch accepted; the task is complete. */
    ACCEPT,
    /** Patch rejected; the coder must revise (single-pass scope: terminates as REJECTED). */
    REJECT,
    /** Reviewer cannot decide; needs human judgement. */
    NEEDS_HUMAN
}
