package com.anthropic.agentkit.infrastructure.llm;

import com.anthropic.agentkit.infrastructure.config.AppConfig;

import java.util.Objects;

/**
 * Selects the provider-specific LangChain4j client factory.
 *
 * @author zhourui(V33215020)
 * @since 2026-06-13
 */
public final class LlmClientFactories {

    private LlmClientFactories() {
    }

    /**
     * @param config runtime model configuration
     * @return configured LangChain4j-backed LLM client
     */
    public static LangChain4jLlmClient create(AppConfig config) {
        Objects.requireNonNull(config, "config");
        return factoryFor(config).create(config);
    }

    private static LlmClientFactory factoryFor(AppConfig config) {
        return switch (config.provider()) {
            case ANTHROPIC -> AnthropicLlmClientFactory.withCacheEnabled();
            case OPENAI -> new OpenAiLlmClientFactory();
        };
    }
}
