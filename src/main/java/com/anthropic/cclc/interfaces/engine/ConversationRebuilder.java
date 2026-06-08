package com.anthropic.cclc.interfaces.engine;

import com.anthropic.cclc.domain.conversation.Conversation;
import com.anthropic.cclc.domain.conversation.SessionId;

import java.util.List;

/**
 * Rebuilds a {@link Conversation} from host-supplied history plus the current
 * user message. The engine is stateless: agent-web owns the session, so every
 * run reconstructs the conversation from scratch. Stub for Red.
 *
 * @author zhourui(V33215020)
 * @since 2026-06-08
 */
public final class ConversationRebuilder {

    public Conversation from(SessionId sessionId, List<TurnMessage> history, String userMessage) {
        return new Conversation(sessionId);
    }
}
