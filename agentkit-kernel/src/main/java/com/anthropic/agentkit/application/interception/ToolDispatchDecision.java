package com.anthropic.agentkit.application.interception;

/** Legal blocking decisions before permission and tool execution. */
public sealed interface ToolDispatchDecision
        permits ToolDispatchDecision.Continue, ToolDispatchDecision.Deny {

    record Continue() implements ToolDispatchDecision {
    }

    record Deny(String reason) implements ToolDispatchDecision {
        public Deny {
            reason = InterceptorDecisionReason.require(reason);
        }
    }

    static ToolDispatchDecision continueDispatch() {
        return new Continue();
    }

    static ToolDispatchDecision deny(String reason) {
        return new Deny(reason);
    }
}
