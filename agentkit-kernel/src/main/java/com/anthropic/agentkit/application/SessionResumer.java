package com.anthropic.agentkit.application;

import com.anthropic.agentkit.domain.conversation.Conversation;
import com.anthropic.agentkit.domain.conversation.SessionId;
import com.anthropic.agentkit.domain.message.ChatMessage;
import com.anthropic.agentkit.domain.port.ChatMemoryStore;

import java.util.List;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class SessionResumer {

    private static final Logger log = LoggerFactory.getLogger(SessionResumer.class);

    private final ChatMemoryStore store;

    public SessionResumer(ChatMemoryStore store) {
        this.store = Objects.requireNonNull(store, "store");
    }

    public Conversation resume(SessionId sessionId) {
        Objects.requireNonNull(sessionId, "sessionId");
        List<ChatMessage> messages = store.load(sessionId);
        if (messages.isEmpty()) {
            log.warn("session resume failed: sessionId={}, reason=not_found", sessionId);
            throw new SessionNotFoundException(sessionId);
        }
        Conversation conversation = new Conversation(sessionId);
        for (ChatMessage message : messages) {
            conversation.append(message);
        }
        log.info("session resumed: sessionId={}, messages={}, replayTools=false", sessionId, messages.size());
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
