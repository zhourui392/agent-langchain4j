package com.anthropic.agentkit.domain.message;

import com.anthropic.agentkit.domain.tool.ToolUseRequest;

import java.util.List;
import java.util.Objects;

public record AiMessage(String text, List<ToolUseRequest> toolUseRequests) implements ChatMessage {
    public AiMessage {
        Objects.requireNonNull(text, "text");
        toolUseRequests = toolUseRequests == null ? List.of() : List.copyOf(toolUseRequests);
    }

    public static AiMessage text(String text) {
        return new AiMessage(text, List.of());
    }

    public static AiMessage of(String text, List<ToolUseRequest> toolUseRequests) {
        return new AiMessage(text, toolUseRequests);
    }

    public boolean hasToolUseRequests() {
        return !toolUseRequests.isEmpty();
    }

    @Override
    public Role role() {
        return Role.AI;
    }
}
