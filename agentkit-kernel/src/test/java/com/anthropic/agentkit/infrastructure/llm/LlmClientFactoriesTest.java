package com.anthropic.agentkit.infrastructure.llm;

import com.anthropic.agentkit.domain.permission.PermissionMode;
import com.anthropic.agentkit.infrastructure.config.AppConfig;
import com.anthropic.agentkit.infrastructure.config.LlmProvider;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LlmClientFactoriesTest {

    @Test
    void createsOpenAiClientFromProviderConfig() {
        AppConfig config = new AppConfig(
                "sk-fake-for-build-test",
                "gpt-5.5",
                1024,
                "https://www.packyapi.com/v1",
                PermissionMode.BYPASS,
                LlmProvider.OPENAI);

        LangChain4jLlmClient client = LlmClientFactories.create(config);

        assertThat(client).isNotNull();
    }

    @Test
    void createsAnthropicClientFromProviderConfig() {
        AppConfig config = new AppConfig(
                "sk-fake-for-build-test",
                "claude-haiku",
                1024,
                null,
                PermissionMode.BYPASS,
                LlmProvider.ANTHROPIC);

        LangChain4jLlmClient client = LlmClientFactories.create(config);

        assertThat(client).isNotNull();
    }
}
