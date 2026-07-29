package com.anthropic.agentkit.application.interception;

/** Legal blocking decisions before context-policy evaluation. */
public sealed interface CompactionDecision
        permits CompactionDecision.Continue, CompactionDecision.Deny {

    record Continue() implements CompactionDecision {
    }

    record Deny(String reason) implements CompactionDecision {
        public Deny {
            if (reason == null || reason.isBlank()) {
                throw new IllegalArgumentException(
                        "interceptor denial reason must not be blank");
            }
            reason = reason.trim();
        }
    }

    static CompactionDecision continueCompaction() {
        return new Continue();
    }

    static CompactionDecision deny(String reason) {
        return new Deny(reason);
    }
}
