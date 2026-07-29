package com.anthropic.agentkit.domain.port;

import com.anthropic.agentkit.domain.agent.ModelIdentity;
import com.anthropic.agentkit.domain.message.AiMessage;

public interface LlmClient {

    LlmCall streamChat(ChatRequest request, StreamHandler handler);

    default ModelIdentity modelIdentity() {
        return ModelIdentity.unknown();
    }

    interface StreamHandler {
        void onPartialText(String delta);

        default void onComplete(AiMessage message) {
        }

        default void onError(Throwable error) {
        }

        default void onUsage(int inputTokens, int outputTokens, int cacheReadInputTokens) {
        }

        static StreamHandler noop() {
            return delta -> { };
        }
    }
}
