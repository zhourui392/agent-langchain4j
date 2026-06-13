package com.anthropic.cclc.infrastructure.llm;

import com.anthropic.cclc.infrastructure.config.AppConfig;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;

import java.time.Duration;
import java.util.Objects;

/**
 * Builds OpenAI-compatible streaming clients.
 *
 * @author zhourui(V33215020)
 * @since 2026-06-13
 */
public final class OpenAiLlmClientFactory implements LlmClientFactory {

    @Override
    public LangChain4jLlmClient create(AppConfig config) {
        Objects.requireNonNull(config, "config");
        var builder = OpenAiStreamingChatModel.builder()
                .apiKey(config.apiKey())
                .modelName(config.model())
                .maxCompletionTokens(config.maxTokens())
                .timeout(Duration.ofSeconds(60))
                .logRequests(true)
                .logResponses(true);
        config.baseUrlIfPresent().ifPresent(builder::baseUrl);
        return new LangChain4jLlmClient(builder.build());
    }
}
