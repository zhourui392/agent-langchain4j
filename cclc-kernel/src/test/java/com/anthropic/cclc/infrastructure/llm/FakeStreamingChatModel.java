package com.anthropic.cclc.infrastructure.llm;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.output.TokenUsage;

import java.util.ArrayList;
import java.util.List;

final class FakeStreamingChatModel implements StreamingChatModel {

    private final List<String> tokens = new ArrayList<>();
    private final List<ToolExecutionRequest> toolRequests = new ArrayList<>();
    private final List<ChatRequest> capturedRequests = new ArrayList<>();
    private String completionText = "";
    private Throwable failure;
    private TokenUsage usage;

    List<ChatRequest> capturedRequests() {
        return List.copyOf(capturedRequests);
    }

    FakeStreamingChatModel tokens(String... ts) {
        tokens.addAll(List.of(ts));
        return this;
    }

    FakeStreamingChatModel completionText(String text) {
        this.completionText = text;
        return this;
    }

    FakeStreamingChatModel toolRequest(String id, String name, String argumentsJson) {
        toolRequests.add(ToolExecutionRequest.builder()
                .id(id).name(name).arguments(argumentsJson).build());
        return this;
    }

    FakeStreamingChatModel failure(Throwable t) {
        this.failure = t;
        return this;
    }

    FakeStreamingChatModel usage(TokenUsage usage) {
        this.usage = usage;
        return this;
    }

    @Override
    public void chat(ChatRequest request, StreamingChatResponseHandler handler) {
        capturedRequests.add(request);
        if (failure != null) {
            handler.onError(failure);
            return;
        }
        for (String t : tokens) {
            handler.onPartialResponse(t);
        }
        dev.langchain4j.data.message.AiMessage aiMessage = toolRequests.isEmpty()
                ? dev.langchain4j.data.message.AiMessage.from(completionText)
                : dev.langchain4j.data.message.AiMessage.from(completionText, toolRequests);
        handler.onCompleteResponse(ChatResponse.builder()
                .aiMessage(aiMessage)
                .tokenUsage(usage)
                .build());
    }
}
