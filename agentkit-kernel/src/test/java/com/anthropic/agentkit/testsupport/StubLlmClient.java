package com.anthropic.agentkit.testsupport;

import com.anthropic.agentkit.domain.message.AiMessage;
import com.anthropic.agentkit.domain.port.ChatRequest;
import com.anthropic.agentkit.domain.port.LlmCall;
import com.anthropic.agentkit.domain.port.LlmClient;
import com.anthropic.agentkit.domain.port.LlmClient.StreamHandler;

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
    public LlmCall streamChat(ChatRequest request, StreamHandler handler) {
        captured.add(request);
        AiMessage next = queue.pollFirst();
        if (next == null) {
            AssertionError err = new AssertionError("StubLlmClient: response queue exhausted");
            handler.onError(err);
            throw err;
        }
        return LlmCall.start(handler, guarded -> {
            if (!next.text().isEmpty()) {
                guarded.onPartialText(next.text());
            }
            guarded.onComplete(next);
        });
    }

    public List<ChatRequest> capturedRequests() {
        return List.copyOf(captured);
    }
}
