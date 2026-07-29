package com.anthropic.agentkit.infrastructure.memory;

import com.anthropic.agentkit.domain.agent.RunId;
import com.anthropic.agentkit.domain.agent.WorkspaceId;
import com.anthropic.agentkit.domain.conversation.SessionId;
import com.anthropic.agentkit.domain.message.AiMessage;
import com.anthropic.agentkit.domain.permission.Decision;
import com.anthropic.agentkit.domain.suspension.ApprovalRequest;
import com.anthropic.agentkit.domain.suspension.InputRequest;
import com.anthropic.agentkit.domain.suspension.PlannedToolInvocation;
import com.anthropic.agentkit.domain.suspension.RunSuspension;
import com.anthropic.agentkit.domain.suspension.SuspensionId;
import com.anthropic.agentkit.domain.suspension.SuspensionScope;
import com.anthropic.agentkit.domain.tool.ToolUseId;
import com.anthropic.agentkit.domain.tool.ToolUseRequest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Explicit wire codec for durable pending requests; raw resume tokens are never included. */
final class RunSuspensionJsonCodec {

    private static final ObjectMapper JSON = new ObjectMapper();

    private RunSuspensionJsonCodec() {
    }

    static String toJson(RunSuspension suspension) throws IOException {
        return toNode(suspension).toString();
    }

    static RunSuspension fromJson(String json) throws IOException {
        JsonNode root = JSON.readTree(json);
        if (root == null || !root.isObject()) {
            throw new IOException("run suspension must be a JSON object");
        }
        return fromNode(root);
    }

    static ObjectNode toNode(RunSuspension suspension) throws IOException {
        ObjectNode root = commonNode(suspension);
        switch (suspension) {
            case RunSuspension.WaitingForApproval approval -> {
                root.put("kind", "approval");
                root.set("invocations", plannedNode(approval.request()));
                root.set("pendingAssistantMessage", messageNode(
                        approval.pendingAssistantMessage()));
            }
            case RunSuspension.WaitingForInput input -> {
                root.put("kind", "input");
                root.put("prompt", input.request().prompt());
                root.set("requestMetadata", JSON.valueToTree(input.request().metadata()));
            }
        }
        return root;
    }

    static RunSuspension fromNode(JsonNode root) throws IOException {
        SuspensionId id = SuspensionId.of(requiredText(root, "suspensionId"));
        SuspensionScope scope = readScope(root);
        return switch (requiredText(root, "kind")) {
            case "approval" -> new RunSuspension.WaitingForApproval(
                    id, scope, readApproval(root), readPendingMessage(root));
            case "input" -> new RunSuspension.WaitingForInput(
                    id, scope, new InputRequest(
                            requiredText(root, "prompt"), readMetadata(root)));
            default -> throw new IOException("unknown run suspension kind");
        };
    }

    private static ObjectNode commonNode(RunSuspension suspension) {
        ObjectNode root = JSON.createObjectNode();
        root.put("suspensionId", suspension.id().value());
        root.put("sessionId", suspension.scope().sessionId().value());
        root.put("workspaceId", suspension.scope().workspaceId().value());
        root.put("originatingRunId", suspension.scope().originatingRunId().value());
        return root;
    }

    private static ArrayNode plannedNode(ApprovalRequest request) {
        ArrayNode array = JSON.createArrayNode();
        for (PlannedToolInvocation item : request.invocations()) {
            ObjectNode node = array.addObject();
            node.put("toolUseId", item.request().id().value());
            node.put("toolName", item.request().toolName());
            node.put("argumentsJson", item.request().argumentsJson());
            node.put("decision", item.decision().name());
        }
        return array;
    }

    private static ApprovalRequest readApproval(JsonNode root) throws IOException {
        JsonNode array = required(root, "invocations");
        if (!array.isArray()) {
            throw new IOException("invocations must be an array");
        }
        java.util.ArrayList<PlannedToolInvocation> items = new java.util.ArrayList<>();
        for (JsonNode node : array) {
            ToolUseRequest request = new ToolUseRequest(
                    new ToolUseId(requiredText(node, "toolUseId")),
                    requiredText(node, "toolName"), requiredText(node, "argumentsJson"));
            items.add(new PlannedToolInvocation(
                    request, Decision.valueOf(requiredText(node, "decision"))));
        }
        return new ApprovalRequest(List.copyOf(items));
    }

    private static AiMessage readPendingMessage(JsonNode root) throws IOException {
        return (AiMessage) MessageJsonCodec.fromJson(
                required(root, "pendingAssistantMessage").toString());
    }

    private static ObjectNode messageNode(AiMessage message) throws IOException {
        return (ObjectNode) JSON.readTree(MessageJsonCodec.toJson(message));
    }

    private static SuspensionScope readScope(JsonNode root) throws IOException {
        return new SuspensionScope(
                SessionId.of(requiredText(root, "sessionId")),
                WorkspaceId.of(requiredText(root, "workspaceId")),
                RunId.of(requiredText(root, "originatingRunId")));
    }

    private static Map<String, String> readMetadata(JsonNode root) throws IOException {
        JsonNode node = required(root, "requestMetadata");
        if (!node.isObject()) {
            throw new IOException("requestMetadata must be an object");
        }
        Map<String, String> result = new LinkedHashMap<>();
        node.fields().forEachRemaining(field ->
                result.put(field.getKey(), field.getValue().asText()));
        return Map.copyOf(result);
    }

    private static JsonNode required(JsonNode root, String field) throws IOException {
        JsonNode value = root.get(field);
        if (value == null || value.isNull()) {
            throw new IOException("missing run suspension field: " + field);
        }
        return value;
    }

    private static String requiredText(JsonNode root, String field) throws IOException {
        JsonNode value = required(root, field);
        if (!value.isTextual()) {
            throw new IOException("run suspension field is not text: " + field);
        }
        return value.asText();
    }
}
