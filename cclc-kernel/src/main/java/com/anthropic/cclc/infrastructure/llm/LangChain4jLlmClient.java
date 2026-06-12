package com.anthropic.cclc.infrastructure.llm;

import com.anthropic.cclc.domain.message.AiMessage;
import com.anthropic.cclc.domain.message.ChatMessage;
import com.anthropic.cclc.domain.port.ChatRequest;
import com.anthropic.cclc.domain.port.LlmClient;
import com.anthropic.cclc.domain.port.ToolSpec;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.anthropic.AnthropicTokenUsage;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.output.TokenUsage;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public final class LangChain4jLlmClient implements LlmClient {

    private final StreamingChatModel model;

    public LangChain4jLlmClient(StreamingChatModel model) {
        this.model = Objects.requireNonNull(model, "model");
    }

    @Override
    public void streamChat(ChatRequest request, StreamHandler handler) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(handler, "handler");
        dev.langchain4j.model.chat.request.ChatRequest lcRequest = buildLcRequest(request);
        HandlerBridge bridge = new HandlerBridge(handler);
        model.chat(lcRequest, bridge);
        bridge.awaitTerminalSignal();
    }

    private static dev.langchain4j.model.chat.request.ChatRequest buildLcRequest(ChatRequest req) {
        List<dev.langchain4j.data.message.ChatMessage> lcMessages = new ArrayList<>();
        if (!req.systemPrompt().isEmpty()) {
            lcMessages.add(dev.langchain4j.data.message.SystemMessage.from(req.systemPrompt()));
        }
        for (ChatMessage m : req.messages()) {
            lcMessages.add(MessageMapper.toLc(m));
        }
        var builder = dev.langchain4j.model.chat.request.ChatRequest.builder()
                .messages(lcMessages);
        List<ToolSpecification> lcSpecs = mapToolSpecs(req.tools());
        if (!lcSpecs.isEmpty()) {
            builder.toolSpecifications(lcSpecs);
        }
        return builder.build();
    }

    private static List<ToolSpecification> mapToolSpecs(List<ToolSpec> specs) {
        List<ToolSpecification> mapped = new ArrayList<>(specs.size());
        for (ToolSpec s : specs) {
            mapped.add(ToolSpecificationMapper.toLc(s));
        }
        return mapped;
    }

    private static final class HandlerBridge implements StreamingChatResponseHandler {

        private final StreamHandler handler;
        private final CountDownLatch terminalSignal = new CountDownLatch(1);

        HandlerBridge(StreamHandler handler) {
            this.handler = handler;
        }

        @Override
        public void onPartialResponse(String token) {
            handler.onPartialText(token);
        }

        @Override
        public void onCompleteResponse(ChatResponse response) {
            try {
                reportUsage(response);
                handler.onComplete((AiMessage) MessageMapper.toDomain(response.aiMessage()));
            } finally {
                terminalSignal.countDown();
            }
        }

        private void reportUsage(ChatResponse response) {
            TokenUsage usage = response.tokenUsage();
            if (usage == null) {
                return;
            }
            handler.onUsage(
                    orZero(usage.inputTokenCount()),
                    orZero(usage.outputTokenCount()),
                    cacheReadInputTokens(usage));
        }

        private static int cacheReadInputTokens(TokenUsage usage) {
            if (usage instanceof AnthropicTokenUsage anthropicUsage) {
                return orZero(anthropicUsage.cacheReadInputTokens());
            }
            return 0;
        }

        private static int orZero(Integer value) {
            return value == null ? 0 : value;
        }

        @Override
        public void onError(Throwable error) {
            try {
                handler.onError(error);
            } finally {
                terminalSignal.countDown();
            }
        }

        void awaitTerminalSignal() {
            try {
                boolean signaled = terminalSignal.await(90, TimeUnit.SECONDS);
                if (!signaled) {
                    handler.onError(new IllegalStateException(
                            "LLM stream timed out after 90s without onComplete/onError"));
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted while waiting for LLM stream", e);
            }
        }
    }
}
