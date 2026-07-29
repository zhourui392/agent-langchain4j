package com.anthropic.agentkit.application.interception;

/** Legal validation decisions for a proposed run stop. */
public sealed interface RunStopDecision
        permits RunStopDecision.Continue, RunStopDecision.Deny {

    record Continue() implements RunStopDecision {
    }

    record Deny(String reason) implements RunStopDecision {
        public Deny {
            if (reason == null || reason.isBlank()) {
                throw new IllegalArgumentException(
                        "interceptor denial reason must not be blank");
            }
            reason = reason.trim();
        }
    }

    static RunStopDecision continueStop() {
        return new Continue();
    }

    static RunStopDecision deny(String reason) {
        return new Deny(reason);
    }
}
