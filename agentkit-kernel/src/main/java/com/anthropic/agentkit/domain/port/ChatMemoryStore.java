package com.anthropic.agentkit.domain.port;

import com.anthropic.agentkit.domain.conversation.SessionId;
import com.anthropic.agentkit.domain.message.ChatMessage;

import java.util.List;

public interface ChatMemoryStore {

    List<ChatMessage> load(SessionId sessionId);

    void save(SessionId sessionId, List<ChatMessage> messages);

    void delete(SessionId sessionId);
}
