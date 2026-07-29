package com.anthropic.agentkit.application.interception;

/** Legal blocking decisions before context-policy evaluation. */
public sealed interface CompactionDecision
        permits CompactionDecision.Continue, CompactionDecision.Deny {

    record Continue() implements CompactionDecision {
    }

    record Deny(String reason) implements CompactionDecision {
        public Deny {
            reason = InterceptorDecisionReason.require(reason);
        }
    }

    static CompactionDecision continueCompaction() {
        return new Continue();
    }

    static CompactionDecision deny(String reason) {
        return new Deny(reason);
    }
}
