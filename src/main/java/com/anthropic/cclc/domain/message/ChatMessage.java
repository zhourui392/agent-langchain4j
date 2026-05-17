package com.anthropic.cclc.domain.message;

public sealed interface ChatMessage
        permits UserMessage, AiMessage, SystemMessage, ToolResultMessage {

    Role role();

    String text();
}
