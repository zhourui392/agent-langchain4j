package com.anthropic.agentkit.application.interception;

import com.anthropic.agentkit.domain.message.ChatMessage;

import java.util.List;

/** Legal blocking decisions before a provider call. */
public sealed interface LlmCallDecision
        permits LlmCallDecision.Continue, LlmCallDecision.Deny,
        LlmCallDecision.ReplaceContext {

    record Continue() implements LlmCallDecision {
    }

    record Deny(String reason) implements LlmCallDecision {
        public Deny {
            reason = InterceptorDecisionReason.require(reason);
        }
    }

    record ReplaceContext(List<ChatMessage> messages) implements LlmCallDecision {
        public ReplaceContext {
            messages = List.copyOf(messages);
        }
    }

    static LlmCallDecision continueCall() {
        return new Continue();
    }

    static LlmCallDecision deny(String reason) {
        return new Deny(reason);
    }

    static LlmCallDecision replaceContext(List<ChatMessage> messages) {
        return new ReplaceContext(messages);
    }

}
