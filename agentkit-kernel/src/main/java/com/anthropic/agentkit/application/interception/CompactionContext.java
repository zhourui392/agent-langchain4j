package com.anthropic.agentkit.application.interception;

import com.anthropic.agentkit.domain.agent.AgentRunContext;
import com.anthropic.agentkit.domain.conversation.CompactionBoundary;
import com.anthropic.agentkit.domain.message.ChatMessage;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Immutable snapshot before a context policy evaluates compaction. */
public record CompactionContext(
        AgentRunContext runContext,
        List<ChatMessage> messages,
        Optional<CompactionBoundary> previousBoundary,
        CompactionCause cause) {

    public CompactionContext {
        Objects.requireNonNull(runContext, "runContext");
        messages = List.copyOf(Objects.requireNonNull(messages, "messages"));
        Objects.requireNonNull(previousBoundary, "previousBoundary");
        Objects.requireNonNull(cause, "cause");
    }
}
