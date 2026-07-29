package com.anthropic.agentkit.infrastructure.llm;

import com.anthropic.agentkit.domain.message.AiMessage;
import com.anthropic.agentkit.domain.message.ChatMessage;
import com.anthropic.agentkit.domain.agent.ModelIdentity;
import com.anthropic.agentkit.domain.port.ChatRequest;
import com.anthropic.agentkit.domain.port.LlmCall;
import com.anthropic.agentkit.domain.port.LlmClient;
import com.anthropic.agentkit.domain.port.ToolSpec;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.anthropic.AnthropicTokenUsage;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.output.TokenUsage;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class LangChain4jLlmClient implements LlmClient {

    private static final Logger log = LoggerFactory.getLogger(LangChain4jLlmClient.class);

    private final StreamingChatModel model;
    private final ModelIdentity identity;

    public LangChain4jLlmClient(StreamingChatModel model) {
        this(model, ModelIdentity.unknown());
    }

    public LangChain4jLlmClient(
            StreamingChatModel model, ModelIdentity identity) {
        this.model = Objects.requireNonNull(model, "model");
        this.identity = Objects.requireNonNull(identity, "identity");
    }

    @Override
    public LlmCall streamChat(ChatRequest request, StreamHandler handler) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(handler, "handler");
        long startNs = System.nanoTime();
        log.info("lc4j stream started: messages={}, tools={}", request.messages().size(), request.tools().size());
        dev.langchain4j.model.chat.request.ChatRequest lcRequest;
        try {
            lcRequest = buildLcRequest(request);
        } catch (IllegalArgumentException failure) {
            throw ProviderFailureMapper.schemaIncompatible(failure);
        }
        LlmCall call = LlmCall.start(handler, guarded -> {
            try {
                model.chat(lcRequest, new HandlerBridge(guarded));
            } catch (RuntimeException failure) {
                guarded.onError(ProviderFailureMapper.toDomain(failure));
            }
        });
        call.completion().whenComplete((message, failure) ->
                log.info("lc4j stream finished: durationMs={}, success={}",
                        elapsedMs(startNs), failure == null));
        return call;
    }

    @Override
    public ModelIdentity modelIdentity() {
        return identity;
    }

    private static dev.langchain4j.model.chat.request.ChatRequest buildLcRequest(ChatRequest req) {
        log.debug("building lc4j request: systemPromptChars={}, messages={}, tools={}",
                req.systemPrompt().length(), req.messages().size(), req.tools().size());
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

        HandlerBridge(StreamHandler handler) {
            this.handler = handler;
        }

        @Override
        public void onPartialResponse(String token) {
            handler.onPartialText(token);
        }

        @Override
        public void onCompleteResponse(ChatResponse response) {
            reportUsage(response);
            handler.onComplete((AiMessage) MessageMapper.toDomain(response.aiMessage()));
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
            handler.onError(ProviderFailureMapper.toDomain(error));
        }
    }

    private static long elapsedMs(long startNs) {
        return (System.nanoTime() - startNs) / 1_000_000L;
    }
}
