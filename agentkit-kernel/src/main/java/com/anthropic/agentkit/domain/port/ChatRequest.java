package com.anthropic.agentkit.domain.port;

import com.anthropic.agentkit.domain.message.ChatMessage;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public record ChatRequest(String systemPrompt, List<ChatMessage> messages, List<ToolSpec> tools) {

    public ChatRequest {
        Objects.requireNonNull(systemPrompt, "systemPrompt");
        messages = messages == null ? List.of() : List.copyOf(messages);
        tools = tools == null ? List.of() : List.copyOf(tools);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String systemPrompt = "";
        private final List<ChatMessage> messages = new ArrayList<>();
        private final List<ToolSpec> tools = new ArrayList<>();

        public Builder systemPrompt(String systemPrompt) {
            this.systemPrompt = systemPrompt;
            return this;
        }

        public Builder message(ChatMessage message) {
            messages.add(message);
            return this;
        }

        public Builder messages(List<ChatMessage> messages) {
            this.messages.addAll(messages);
            return this;
        }

        public Builder tool(ToolSpec spec) {
            tools.add(spec);
            return this;
        }

        public ChatRequest build() {
            return new ChatRequest(systemPrompt, messages, tools);
        }
    }
}
