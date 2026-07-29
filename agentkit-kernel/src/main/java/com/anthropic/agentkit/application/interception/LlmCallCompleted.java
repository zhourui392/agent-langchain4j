package com.anthropic.agentkit.application.interception;

import com.anthropic.agentkit.domain.agent.StopReason;
import com.anthropic.agentkit.domain.message.AiMessage;

import java.util.Objects;
import java.util.Optional;

/** Observed completion of an actual provider call. */
public record LlmCallCompleted(
        LlmCallContext call,
        Optional<AiMessage> message,
        Optional<StopReason> stopReason,
        Optional<String> errorDetail) {

    public LlmCallCompleted {
        Objects.requireNonNull(call, "call");
        Objects.requireNonNull(message, "message");
        Objects.requireNonNull(stopReason, "stopReason");
        Objects.requireNonNull(errorDetail, "errorDetail");
        errorDetail = errorDetail.filter(detail -> !detail.isBlank());
        if (message.isPresent() == stopReason.isPresent()) {
            throw new IllegalArgumentException(
                    "LLM completion must contain either a message or a stop reason");
        }
    }
}
