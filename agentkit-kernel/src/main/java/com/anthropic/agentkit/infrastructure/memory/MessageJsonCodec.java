package com.anthropic.agentkit.infrastructure.memory;

import com.anthropic.agentkit.domain.message.AiMessage;
import com.anthropic.agentkit.domain.message.ChatMessage;
import com.anthropic.agentkit.domain.message.SystemMessage;
import com.anthropic.agentkit.domain.message.ToolResultMessage;
import com.anthropic.agentkit.domain.message.UserMessage;
import com.anthropic.agentkit.domain.tool.ToolUseId;
import com.anthropic.agentkit.domain.tool.ToolUseRequest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

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
                root.put("text", tr.text());
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
            case "toolResult" -> ToolResultMessage.of(
                    new ToolUseId(node.get("toolUseId").asText()),
                    node.get("text").asText());
            default -> throw new IOException("unknown message type: " + type);
        };
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
