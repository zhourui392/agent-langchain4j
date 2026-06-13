package com.anthropic.cclc.infrastructure.llm;

import com.anthropic.cclc.infrastructure.config.AppConfig;

/**
 * Builds an LLM client for one configured provider.
 *
 * @author zhourui(V33215020)
 * @since 2026-06-13
 */
public interface LlmClientFactory {

    /**
     * @param config runtime model configuration
     * @return configured LangChain4j-backed LLM client
     */
    LangChain4jLlmClient create(AppConfig config);
}
