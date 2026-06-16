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

@EnabledIfEnvironmentVariable(named = "AK_API_KEY", matches = ".+")
class OpenAiEndToEndSmokeIT {

    @Test
    void sayHelloReachesOpenAiCompatibleEndpointAndReturnsNonEmptyText() {
        AppConfig config = new AppConfig(
                System.getenv("AK_API_KEY"),
                openAiModel(),
                ConfigLoader.DEFAULT_MAX_TOKENS,
                openAiBaseUrl(),
                PermissionMode.BYPASS,
                LlmProvider.OPENAI);
        LangChain4jLlmClient client = LlmClientFactories.create(config);

        ChatRequest request = ChatRequest.builder()
                .systemPrompt("You are a brief assistant. Reply in 5 words or less.")
                .message(UserMessage.of("say hello"))
                .build();

        AtomicReference<AiMessage> completed = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        client.streamChat(request, new StreamHandler() {
            @Override public void onPartialText(String delta) {}
            @Override public void onComplete(AiMessage message) { completed.set(message); }
            @Override public void onError(Throwable error) { failure.set(error); }
        });

        assertThat(failure.get()).as("no error from API").isNull();
        assertThat(completed.get()).as("LLM produced an AiMessage").isNotNull();
        assertThat(completed.get().text()).as("response is non-empty").isNotBlank();
    }

    private static String openAiBaseUrl() {
        String configured = System.getenv("AK_BASE_URL");
        return configured == null || configured.isBlank()
                ? "https://www.packyapi.com/v1"
                : configured;
    }

    private static String openAiModel() {
        String configured = System.getenv("AK_MODEL");
        return configured == null || configured.isBlank()
                ? ConfigLoader.DEFAULT_OPENAI_MODEL
                : configured;
    }
}
