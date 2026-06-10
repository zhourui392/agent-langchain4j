package com.anthropic.cclc.application;

import com.anthropic.cclc.domain.conversation.Conversation;
import com.anthropic.cclc.domain.conversation.SessionId;
import com.anthropic.cclc.domain.message.ChatMessage;
import com.anthropic.cclc.domain.port.ChatMemoryStore;

import java.util.List;
import java.util.Objects;

public final class SessionResumer {

    private final ChatMemoryStore store;

    public SessionResumer(ChatMemoryStore store) {
        this.store = Objects.requireNonNull(store, "store");
    }

    public Conversation resume(SessionId sessionId) {
        Objects.requireNonNull(sessionId, "sessionId");
        List<ChatMessage> messages = store.load(sessionId);
        if (messages.isEmpty()) {
            throw new SessionNotFoundException(sessionId);
        }
        Conversation conversation = new Conversation(sessionId);
        for (ChatMessage message : messages) {
            conversation.append(message);
        }
        return conversation;
    }

    public static final class SessionNotFoundException extends RuntimeException {

        private final SessionId sessionId;

        public SessionNotFoundException(SessionId sessionId) {
            super("session not found: " + sessionId);
            this.sessionId = sessionId;
        }

        public SessionId sessionId() {
            return sessionId;
        }
    }
}
