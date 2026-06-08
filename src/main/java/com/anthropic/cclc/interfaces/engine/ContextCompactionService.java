package com.anthropic.cclc.interfaces.engine;

import com.anthropic.cclc.domain.conversation.Conversation;
import com.anthropic.cclc.domain.conversation.TokenBudget;
import com.anthropic.cclc.domain.port.LlmClient;

import java.util.Objects;

/**
 * Compacts an over-budget conversation by summarising older messages into a
 * single boundary message and keeping the recent tail. Returns a new
 * {@link Conversation}; the domain aggregate is never mutated, and
 * {@code AgentExecutor} is untouched (the engine calls this at run start).
 * Stub for Red.
 *
 * @author zhourui(V33215020)
 * @since 2026-06-08
 */
public final class ContextCompactionService {

    private final LlmClient llm;
    private final TokenBudget budget;
    private final int recentMessages;

    public ContextCompactionService(LlmClient llm, TokenBudget budget, int recentMessages) {
        this.llm = Objects.requireNonNull(llm, "llm");
        this.budget = Objects.requireNonNull(budget, "budget");
        this.recentMessages = recentMessages;
    }

    public Conversation maybeCompact(Conversation conversation) {
        return conversation;
    }
}
