package com.anthropic.agentkit.infrastructure.llm;

import com.anthropic.agentkit.domain.message.AiMessage;
import com.anthropic.agentkit.domain.message.UserMessage;
import com.anthropic.agentkit.domain.permission.PermissionMode;
import com.anthropic.agentkit.domain.port.ChatRequest;
import com.anthropic.agentkit.domain.port.LlmClient.StreamHandler;
import com.anthropic.agentkit.infrastructure.config.AppConfig;
import com.anthropic.agentkit.infrastructure.config.ConfigLoader;
import com.anthropic.agentkit.infrastructure.config.LlmProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfEnvironmentVariable(named = "ANTHROPIC_API_KEY", matches = ".+")
class EndToEndSmokeIT {

    @Test
    void sayHelloReachesAnthropicAndReturnsNonEmptyText() {
        AppConfig config = anthropicConfig();
        LangChain4jLlmClient client = AnthropicLlmClientFactory
                .withCacheEnabled()
                .create(config);

        ChatRequest request = ChatRequest.builder()
                .systemPrompt("You are a brief assistant. Reply in 5 words or less.")
                .message(UserMessage.of("say hello"))
                .build();

        AtomicReference<AiMessage> completed = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        var call = client.streamChat(request, new StreamHandler() {
            @Override public void onPartialText(String delta) {}
            @Override public void onComplete(AiMessage message) { completed.set(message); }
            @Override public void onError(Throwable error) { failure.set(error); }
        });
        call.completion().toCompletableFuture().join();

        assertThat(failure.get()).as("no error from API").isNull();
        assertThat(completed.get()).as("LLM produced an AiMessage").isNotNull();
        assertThat(completed.get().text()).as("response is non-empty").isNotBlank();
    }

    private static AppConfig anthropicConfig() {
        return new AppConfig(
                System.getenv("ANTHROPIC_API_KEY"),
                anthropicModel(),
                ConfigLoader.DEFAULT_MAX_TOKENS,
                System.getenv("ANTHROPIC_BASE_URL"),
                PermissionMode.BYPASS,
                LlmProvider.ANTHROPIC);
    }

    private static String anthropicModel() {
        String configured = System.getenv("AK_MODEL");
        return configured == null || configured.isBlank()
                ? ConfigLoader.DEFAULT_ANTHROPIC_MODEL
                : configured;
    }
}
