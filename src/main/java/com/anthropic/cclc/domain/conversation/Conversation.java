package com.anthropic.cclc.domain.conversation;

import com.anthropic.cclc.domain.message.ChatMessage;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

public final class Conversation {

    private final SessionId sessionId;
    private final List<ChatMessage> messages = new ArrayList<>();
    private final ToolUseInvariantChecker invariants = new ToolUseInvariantChecker();

    public Conversation(SessionId sessionId) {
        this.sessionId = Objects.requireNonNull(sessionId, "sessionId");
    }

    public SessionId sessionId() {
        return sessionId;
    }

    public List<ChatMessage> messages() {
        return Collections.unmodifiableList(messages);
    }

    public void append(ChatMessage message) {
        Objects.requireNonNull(message, "message");
        invariants.onAppend(message);
        messages.add(message);
    }
}
