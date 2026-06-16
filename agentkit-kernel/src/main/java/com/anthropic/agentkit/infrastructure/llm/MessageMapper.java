package com.anthropic.agentkit.infrastructure.llm;

import com.anthropic.agentkit.domain.message.AiMessage;
import com.anthropic.agentkit.domain.message.ChatMessage;
import com.anthropic.agentkit.domain.message.SystemMessage;
import com.anthropic.agentkit.domain.message.ToolResultMessage;
import com.anthropic.agentkit.domain.message.UserMessage;
import com.anthropic.agentkit.domain.tool.ToolUseId;
import com.anthropic.agentkit.domain.tool.ToolUseRequest;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.ToolExecutionResultMessage;

import java.util.List;

public final class MessageMapper {

    private MessageMapper() {
    }

    public static dev.langchain4j.data.message.ChatMessage toLc(ChatMessage message) {
        return switch (message) {
            case UserMessage u -> dev.langchain4j.data.message.UserMessage.from(u.text());
            case SystemMessage s -> dev.langchain4j.data.message.SystemMessage.from(s.text());
            case AiMessage a -> mapAiToLc(a);
            case ToolResultMessage tr -> ToolExecutionResultMessage.from(
                    tr.toolUseId().value(), "", tr.text());
        };
    }

    public static ChatMessage toDomain(dev.langchain4j.data.message.ChatMessage message) {
        return switch (message) {
            case dev.langchain4j.data.message.UserMessage u -> UserMessage.of(u.singleText());
            case dev.langchain4j.data.message.SystemMessage s -> SystemMessage.of(s.text());
            case dev.langchain4j.data.message.AiMessage a -> mapAiToDomain(a);
            case ToolExecutionResultMessage tr -> ToolResultMessage.of(
                    new ToolUseId(tr.id()), tr.text());
            default -> throw new IllegalArgumentException(
                    "unsupported LangChain4j message type: " + message.getClass());
        };
    }

    private static dev.langchain4j.data.message.AiMessage mapAiToLc(AiMessage ai) {
        if (!ai.hasToolUseRequests()) {
            return dev.langchain4j.data.message.AiMessage.from(ai.text());
        }
        List<ToolExecutionRequest> lcRequests = ai.toolUseRequests().stream()
                .map(MessageMapper::toLcRequest)
                .toList();
        return dev.langchain4j.data.message.AiMessage.from(ai.text(), lcRequests);
    }

    private static AiMessage mapAiToDomain(dev.langchain4j.data.message.AiMessage ai) {
        String text = ai.text() == null ? "" : ai.text();
        if (!ai.hasToolExecutionRequests()) {
            return AiMessage.text(text);
        }
        List<ToolUseRequest> domainRequests = ai.toolExecutionRequests().stream()
                .map(MessageMapper::toDomainRequest)
                .toList();
        return AiMessage.of(text, domainRequests);
    }

    private static ToolExecutionRequest toLcRequest(ToolUseRequest req) {
        return ToolExecutionRequest.builder()
                .id(req.id().value())
                .name(req.toolName())
                .arguments(req.argumentsJson())
                .build();
    }

    private static ToolUseRequest toDomainRequest(ToolExecutionRequest req) {
        return new ToolUseRequest(new ToolUseId(req.id()), req.name(), req.arguments());
    }
}
