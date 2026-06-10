package com.anthropic.cclc.domain.port;

import com.anthropic.cclc.domain.conversation.SessionId;
import com.anthropic.cclc.domain.message.ChatMessage;

import java.util.List;

public interface ChatMemoryStore {

    List<ChatMessage> load(SessionId sessionId);

    void save(SessionId sessionId, List<ChatMessage> messages);

    void delete(SessionId sessionId);
}
