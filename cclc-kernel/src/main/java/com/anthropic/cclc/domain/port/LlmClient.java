package com.anthropic.cclc.domain.port;

import com.anthropic.cclc.domain.message.AiMessage;

public interface LlmClient {

    void streamChat(ChatRequest request, StreamHandler handler);

    interface StreamHandler {
        void onPartialText(String delta);

        default void onComplete(AiMessage message) {
        }

        default void onError(Throwable error) {
        }

        default void onUsage(int inputTokens, int outputTokens, int cacheReadInputTokens) {
        }
    }
}
