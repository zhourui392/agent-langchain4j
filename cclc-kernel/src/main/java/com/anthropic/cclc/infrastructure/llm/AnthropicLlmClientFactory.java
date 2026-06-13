package com.anthropic.cclc.infrastructure.llm;

import com.anthropic.cclc.infrastructure.config.AppConfig;
import dev.langchain4j.model.anthropic.AnthropicStreamingChatModel;

import java.time.Duration;
import java.util.Objects;

public final class AnthropicLlmClientFactory implements LlmClientFactory {

    private final CacheBreakpointStrategy cacheStrategy;

    public AnthropicLlmClientFactory(CacheBreakpointStrategy cacheStrategy) {
        this.cacheStrategy = Objects.requireNonNull(cacheStrategy, "cacheStrategy");
    }

    public static AnthropicLlmClientFactory withCacheEnabled() {
        return new AnthropicLlmClientFactory(CacheBreakpointStrategy.enabled());
    }

    @Override
    public LangChain4jLlmClient create(AppConfig config) {
        Objects.requireNonNull(config, "config");
        var builder = AnthropicStreamingChatModel.builder()
                .apiKey(config.apiKey())
                .modelName(config.model())
                .maxTokens(config.maxTokens())
                .timeout(Duration.ofSeconds(60))
                .logRequests(true)
                .logResponses(true)
                .cacheSystemMessages(cacheStrategy.cacheSystemPrompt())
                .cacheTools(cacheStrategy.cacheToolDefinitions());
        config.baseUrlIfPresent()
                .map(AnthropicEndpointResolver::resolveBaseUrl)
                .ifPresent(builder::baseUrl);
        return new LangChain4jLlmClient(builder.build());
    }

    public CacheBreakpointStrategy cacheStrategy() {
        return cacheStrategy;
    }
}
