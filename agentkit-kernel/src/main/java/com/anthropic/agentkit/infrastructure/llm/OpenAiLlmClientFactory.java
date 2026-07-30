package com.anthropic.agentkit.infrastructure.llm;

import com.anthropic.agentkit.domain.agent.ModelIdentity;
import com.anthropic.agentkit.infrastructure.config.AppConfig;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;

import java.time.Duration;
import java.util.Locale;
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
                .logRequests(false)
                .logResponses(false);
        config.baseUrlIfPresent().ifPresent(builder::baseUrl);
        return new LangChain4jLlmClient(builder.build(), new ModelIdentity(
                config.provider().name().toLowerCase(Locale.ROOT), config.model()));
    }
}
