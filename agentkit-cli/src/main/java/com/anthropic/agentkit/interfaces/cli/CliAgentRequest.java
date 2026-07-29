package com.anthropic.agentkit.interfaces.cli;

import com.anthropic.agentkit.domain.conversation.Conversation;

import java.util.Objects;

public record CliAgentRequest(Conversation conversation) {

    public CliAgentRequest {
        Objects.requireNonNull(conversation, "conversation");
    }
}
