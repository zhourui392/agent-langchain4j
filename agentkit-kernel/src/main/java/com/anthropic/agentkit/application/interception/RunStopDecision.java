package com.anthropic.agentkit.application.interception;

/** Legal validation decisions for a proposed run stop. */
public sealed interface RunStopDecision
        permits RunStopDecision.Continue, RunStopDecision.Deny {

    record Continue() implements RunStopDecision {
    }

    record Deny(String reason) implements RunStopDecision {
        public Deny {
            reason = InterceptorDecisionReason.require(reason);
        }
    }

    static RunStopDecision continueStop() {
        return new Continue();
    }

    static RunStopDecision deny(String reason) {
        return new Deny(reason);
    }
}
