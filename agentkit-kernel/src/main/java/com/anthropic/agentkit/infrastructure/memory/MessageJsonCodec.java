package com.anthropic.agentkit.infrastructure.memory;

import com.anthropic.agentkit.domain.message.AiMessage;
import com.anthropic.agentkit.domain.message.ChatMessage;
import com.anthropic.agentkit.domain.message.SystemMessage;
import com.anthropic.agentkit.domain.message.ToolResultMessage;
import com.anthropic.agentkit.domain.message.UserMessage;
import com.anthropic.agentkit.domain.tool.ToolUseId;
import com.anthropic.agentkit.domain.tool.ToolUseRequest;
import com.anthropic.agentkit.domain.tool.ToolResultStatus;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

final class MessageJsonCodec {

    private static final ObjectMapper JSON = new ObjectMapper();

    private MessageJsonCodec() {
    }

    static String toJson(ChatMessage message) {
        ObjectNode root = JSON.createObjectNode();
        switch (message) {
            case UserMessage u -> {
                root.put("type", "user");
                root.put("text", u.text());
            }
            case SystemMessage s -> {
                root.put("type", "system");
                root.put("text", s.text());
            }
            case AiMessage a -> writeAi(root, a);
            case ToolResultMessage tr -> {
                root.put("type", "toolResult");
                root.put("toolUseId", tr.toolUseId().value());
                root.put("status", tr.status().name());
                root.put("text", tr.text());
                ObjectNode metadata = root.putObject("metadata");
                tr.metadata().forEach(metadata::put);
            }
        }
        return root.toString();
    }

    static ChatMessage fromJson(String line) throws IOException {
        JsonNode node = JSON.readTree(line);
        String type = node.get("type").asText();
        return switch (type) {
            case "user" -> UserMessage.of(node.get("text").asText());
            case "system" -> SystemMessage.of(node.get("text").asText());
            case "ai" -> readAi(node);
            case "toolResult" -> readToolResult(node);
            default -> throw new IOException("unknown message type: " + type);
        };
    }

    private static ToolResultMessage readToolResult(JsonNode node) throws IOException {
        ToolResultStatus status = readStatus(node.get("status"));
        Map<String, String> metadata = readMetadata(node.get("metadata"));
        return ToolResultMessage.of(
                new ToolUseId(node.get("toolUseId").asText()),
                status, node.get("text").asText(), metadata);
    }

    private static ToolResultStatus readStatus(JsonNode node) throws IOException {
        if (node == null || node.isNull()) {
            return ToolResultStatus.SUCCESS;
        }
        try {
            return ToolResultStatus.valueOf(node.asText());
        } catch (IllegalArgumentException ex) {
            throw new IOException("unknown tool result status: " + node.asText(), ex);
        }
    }

    private static Map<String, String> readMetadata(JsonNode node) {
        if (node == null || !node.isObject()) {
            return Map.of();
        }
        Map<String, String> metadata = new LinkedHashMap<>();
        node.fields().forEachRemaining(field -> metadata.put(field.getKey(), field.getValue().asText()));
        return Map.copyOf(metadata);
    }

    private static void writeAi(ObjectNode root, AiMessage message) {
        root.put("type", "ai");
        root.put("text", message.text());
        ArrayNode requests = root.putArray("toolUseRequests");
        for (ToolUseRequest req : message.toolUseRequests()) {
            ObjectNode r = requests.addObject();
            r.put("id", req.id().value());
            r.put("toolName", req.toolName());
            r.put("argumentsJson", req.argumentsJson());
        }
    }

    private static AiMessage readAi(JsonNode node) {
        String text = node.get("text").asText();
        List<ToolUseRequest> requests = new ArrayList<>();
        JsonNode arr = node.get("toolUseRequests");
        if (arr != null) {
            for (JsonNode r : arr) {
                requests.add(new ToolUseRequest(
                        new ToolUseId(r.get("id").asText()),
                        r.get("toolName").asText(),
                        r.get("argumentsJson").asText()));
            }
        }
        return AiMessage.of(text, requests);
    }
}
