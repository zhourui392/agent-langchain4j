package com.anthropic.cclc.testsupport;

import com.anthropic.cclc.domain.message.AiMessage;
import com.anthropic.cclc.domain.port.ChatRequest;
import com.anthropic.cclc.domain.port.LlmClient;
import com.anthropic.cclc.domain.port.LlmClient.StreamHandler;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

public final class StubLlmClient implements LlmClient {

    private final Deque<AiMessage> queue = new ArrayDeque<>();
    private final List<ChatRequest> captured = new ArrayList<>();

    public StubLlmClient enqueue(AiMessage message) {
        queue.addLast(message);
        return this;
    }

    @Override
    public void streamChat(ChatRequest request, StreamHandler handler) {
        captured.add(request);
        AiMessage next = queue.pollFirst();
        if (next == null) {
            AssertionError err = new AssertionError("StubLlmClient: response queue exhausted");
            handler.onError(err);
            throw err;
        }
        if (!next.text().isEmpty()) {
            handler.onPartialText(next.text());
        }
        handler.onComplete(next);
    }

    public List<ChatRequest> capturedRequests() {
        return List.copyOf(captured);
    }
}
