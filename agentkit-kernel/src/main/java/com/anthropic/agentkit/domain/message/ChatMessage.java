package com.anthropic.agentkit.domain.message;

public sealed interface ChatMessage
        permits UserMessage, AiMessage, SystemMessage, ToolResultMessage {

    Role role();

    String text();
}
