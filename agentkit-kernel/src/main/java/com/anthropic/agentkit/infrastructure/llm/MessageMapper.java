package com.anthropic.agentkit.infrastructure.llm;

import com.anthropic.agentkit.domain.message.AiMessage;
import com.anthropic.agentkit.domain.message.ChatMessage;
import com.anthropic.agentkit.domain.message.SystemMessage;
import com.anthropic.agentkit.domain.message.ToolResultMessage;
import com.anthropic.agentkit.domain.message.UserMessage;
import com.anthropic.agentkit.domain.tool.ToolResultStatus;
import com.anthropic.agentkit.domain.tool.ToolUseId;
import com.anthropic.agentkit.domain.tool.ToolUseRequest;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.ToolExecutionResultMessage;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class MessageMapper {

    private static final String STATUS_ATTRIBUTE = "agentkit.tool_result_status";

    private MessageMapper() {
    }

    public static dev.langchain4j.data.message.ChatMessage toLc(ChatMessage message) {
        return switch (message) {
            case UserMessage u -> dev.langchain4j.data.message.UserMessage.from(u.text());
            case SystemMessage s -> dev.langchain4j.data.message.SystemMessage.from(s.text());
            case AiMessage a -> mapAiToLc(a);
            case ToolResultMessage tr -> mapToolResultToLc(tr);
        };
    }

    public static ChatMessage toDomain(dev.langchain4j.data.message.ChatMessage message) {
        return switch (message) {
            case dev.langchain4j.data.message.UserMessage u -> UserMessage.of(u.singleText());
            case dev.langchain4j.data.message.SystemMessage s -> SystemMessage.of(s.text());
            case dev.langchain4j.data.message.AiMessage a -> mapAiToDomain(a);
            case ToolExecutionResultMessage tr -> mapToolResultToDomain(tr);
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

    private static ToolExecutionResultMessage mapToolResultToLc(ToolResultMessage result) {
        Map<String, Object> attributes = new LinkedHashMap<>();
        result.metadata().forEach(attributes::put);
        attributes.put(STATUS_ATTRIBUTE, result.status().name());
        return ToolExecutionResultMessage.builder()
                .id(result.toolUseId().value())
                .toolName("")
                .text(result.text())
                .isError(result.isError())
                .attributes(attributes)
                .build();
    }

    private static ToolResultMessage mapToolResultToDomain(ToolExecutionResultMessage result) {
        Map<String, Object> attributes = result.attributes() == null ? Map.of() : result.attributes();
        ToolResultStatus status = readStatus(attributes.get(STATUS_ATTRIBUTE), result.isError());
        Map<String, String> metadata = new LinkedHashMap<>();
        attributes.forEach((key, value) -> {
            if (!STATUS_ATTRIBUTE.equals(key)) {
                metadata.put(key, String.valueOf(value));
            }
        });
        return ToolResultMessage.of(
                new ToolUseId(result.id()), status, result.text(), metadata);
    }

    private static ToolResultStatus readStatus(Object value, Boolean isError) {
        if (value != null) {
            try {
                return ToolResultStatus.valueOf(String.valueOf(value));
            } catch (IllegalArgumentException ignored) {
                // Fall through to the provider's coarse error flag.
            }
        }
        return Boolean.TRUE.equals(isError) ? ToolResultStatus.ERROR : ToolResultStatus.SUCCESS;
    }
}
