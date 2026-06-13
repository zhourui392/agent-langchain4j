package com.anthropic.cclc.infrastructure.llm;

import ch.qos.logback.classic.Level;
import com.anthropic.cclc.infrastructure.config.AppConfig;
import com.anthropic.cclc.testsupport.LogCapture;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PromptCacheTest {

    @Test
    void enabledStrategyMarksCacheBreakpointAfterSystemPrompt() {
        CacheBreakpointStrategy strategy = CacheBreakpointStrategy.enabled();
        assertThat(strategy.cacheSystemPrompt()).isTrue();
    }

    @Test
    void enabledStrategyMarksSecondBreakpointAfterToolDefinitions() {
        CacheBreakpointStrategy strategy = CacheBreakpointStrategy.enabled();
        assertThat(strategy.cacheToolDefinitions()).isTrue();
    }

    @Test
    void disabledStrategyAddsNoBreakpoints() {
        CacheBreakpointStrategy strategy = CacheBreakpointStrategy.disabled();
        assertThat(strategy.cacheSystemPrompt()).isFalse();
        assertThat(strategy.cacheToolDefinitions()).isFalse();
    }

    @Test
    void systemOnlyStrategyOmitsToolBreakpoint() {
        CacheBreakpointStrategy strategy = CacheBreakpointStrategy.systemOnly();
        assertThat(strategy.cacheSystemPrompt()).isTrue();
        assertThat(strategy.cacheToolDefinitions()).isFalse();
    }

    @Test
    void strategyLogsCacheBreakpointSegmentsAtDebugLevel() {
        try (LogCapture logs = LogCapture.forClass(CacheBreakpointStrategy.class, Level.DEBUG)) {
            CacheBreakpointStrategy.enabled();

            assertThat(logs.events()).anySatisfy(event ->
                    assertThat(event.getFormattedMessage())
                            .contains("cache breakpoint strategy configured")
                            .contains("systemPrompt=true")
                            .contains("toolDefinitions=true"));
        }
    }

    @Test
    void factoryCarriesStrategyForRuntimeIntrospection() {
        AnthropicLlmClientFactory factory =
                new AnthropicLlmClientFactory(CacheBreakpointStrategy.enabled());
        assertThat(factory.cacheStrategy()).isEqualTo(CacheBreakpointStrategy.enabled());
    }

    @Test
    void factoryBuildsClientWithoutNetworkCallForFakeKey() {
        AnthropicLlmClientFactory factory =
                new AnthropicLlmClientFactory(CacheBreakpointStrategy.enabled());
        AppConfig config = new AppConfig("sk-fake-for-build-test", "claude-haiku", 1024);

        LangChain4jLlmClient client = factory.create(config);

        assertThat(client).isNotNull();
    }
}
