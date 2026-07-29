package com.anthropic.agentkit.application.context;

import com.anthropic.agentkit.domain.agent.AgentRunContext;
import com.anthropic.agentkit.domain.conversation.Conversation;
import com.anthropic.agentkit.domain.conversation.TokenBudget;
import com.anthropic.agentkit.domain.port.LlmClient;

/** Run-wide policy applied before every agent LLM request. */
@FunctionalInterface
public interface ContextPolicy {

    int DEFAULT_CONTEXT_TOKENS = 180_000;
    int DEFAULT_RECENT_MESSAGES = 30;

    ContextDecision beforeLlmCall(Conversation conversation, AgentRunContext context);

    default ContextDecision recoverFromOverflow(
            Conversation conversation, Throwable failure, AgentRunContext context) {
        return ContextDecision.notApplicableDecision();
    }

    default boolean isContextOverflow(Throwable failure) {
        return false;
    }

    static ContextPolicy standard(LlmClient llm) {
        return new ContextCompactionService(
                llm, TokenBudget.of(DEFAULT_CONTEXT_TOKENS), DEFAULT_RECENT_MESSAGES);
    }

    static ContextPolicy none() {
        return (conversation, context) -> ContextDecision.unchanged();
    }

}
