package com.anthropic.cclc.infrastructure.llm;

import com.anthropic.cclc.domain.message.AiMessage;
import com.anthropic.cclc.domain.message.UserMessage;
import com.anthropic.cclc.domain.port.ChatRequest;
import com.anthropic.cclc.domain.port.LlmClient.StreamHandler;
import com.anthropic.cclc.infrastructure.config.AppConfig;
import com.anthropic.cclc.infrastructure.config.ConfigLoader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfEnvironmentVariable(named = "ANTHROPIC_API_KEY", matches = ".+")
class EndToEndSmokeIT {

    @Test
    void sayHelloReachesAnthropicAndReturnsNonEmptyText() {
        AppConfig config = ConfigLoader.fromSystem().load();
        LangChain4jLlmClient client = AnthropicLlmClientFactory
                .withCacheEnabled()
                .create(config);

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
}
