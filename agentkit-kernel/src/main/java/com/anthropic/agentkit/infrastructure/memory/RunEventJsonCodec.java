package com.anthropic.agentkit.infrastructure.memory;

import com.anthropic.agentkit.domain.agent.AgentUsage;
import com.anthropic.agentkit.domain.agent.BudgetConsumption;
import com.anthropic.agentkit.domain.agent.ModelIdentity;
import com.anthropic.agentkit.domain.agent.ModelUsage;
import com.anthropic.agentkit.domain.agent.RunId;
import com.anthropic.agentkit.domain.agent.StopReason;
import com.anthropic.agentkit.domain.agent.WorkspaceId;
import com.anthropic.agentkit.domain.checkpoint.CheckpointId;
import com.anthropic.agentkit.domain.conversation.CompactionBoundary;
import com.anthropic.agentkit.domain.conversation.SessionId;
import com.anthropic.agentkit.domain.message.AiMessage;
import com.anthropic.agentkit.domain.message.ChatMessage;
import com.anthropic.agentkit.domain.run.RunEvent;
import com.anthropic.agentkit.domain.run.RunEventMetadata;
import com.anthropic.agentkit.domain.suspension.ApprovalDecision;
import com.anthropic.agentkit.domain.suspension.InputAnswer;
import com.anthropic.agentkit.domain.suspension.RunSuspension;
import com.anthropic.agentkit.domain.tool.ToolResult;
import com.anthropic.agentkit.domain.tool.ToolResultStatus;
import com.anthropic.agentkit.domain.tool.ToolSideEffect;
import com.anthropic.agentkit.domain.tool.ToolUseId;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Explicit version-1 wire codec; domain events remain free of Jackson annotations. */
final class RunEventJsonCodec {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> OBJECT_MAP = new TypeReference<>() { };
    private static final RunEventDataPolicy DATA_POLICY = new RunEventDataPolicy(JSON);

    private RunEventJsonCodec() {
    }

    static String toJson(RunEvent event) throws IOException {
        ObjectNode root = metadata(event.metadata());
        switch (event) {
            case RunEvent.RunStarted started -> writeStarted(root, started);
            case RunEvent.LlmCallStarted llm -> {
                root.put("type", "llm_call_started");
                root.put("messageCount", llm.messageCount());
            }
            case RunEvent.AssistantTurnReceived received -> {
                root.put("type", "assistant_turn_received");
                root.set("message", messageNode(received.message()));
            }
            case RunEvent.ToolInvocationStarted started -> {
                root.put("type", "tool_invocation_started");
                root.put("toolUseId", started.toolUseId().value());
            }
            case RunEvent.ToolSideEffectObserved observed ->
                    writeSideEffect(root, observed);
            case RunEvent.ToolInvocationSettled settled -> writeSettled(root, settled);
            case RunEvent.CompactionCompleted compacted -> writeCompaction(root, compacted);
            case RunEvent.RunSuspended suspended -> {
                root.put("type", "run_suspended");
                root.set("suspension", RunSuspensionJsonCodec.toNode(suspended.suspension()));
            }
            case RunEvent.ApprovalSubmitted submitted -> writeApproval(root, submitted);
            case RunEvent.InputAnswered answered -> writeInputAnswer(root, answered);
            case RunEvent.RunStopped stopped -> writeStopped(root, stopped);
        }
        return DATA_POLICY.govern(root).toString();
    }

    static RunEvent fromJson(String line) throws IOException {
        JsonNode root = JSON.readTree(line);
        if (root == null || !root.isObject()) {
            throw new IOException("run event record must be a JSON object");
        }
        RunEventMetadata metadata = readMetadata(root);
        return switch (requiredText(root, "type")) {
            case "run_started" -> readStarted(root, metadata);
            case "llm_call_started" -> new RunEvent.LlmCallStarted(
                    metadata, requiredInt(root, "messageCount"));
            case "assistant_turn_received" -> new RunEvent.AssistantTurnReceived(
                    metadata, (AiMessage) readMessage(required(root, "message")));
            case "tool_invocation_started" -> new RunEvent.ToolInvocationStarted(
                    metadata, new ToolUseId(requiredText(root, "toolUseId")));
            case "tool_side_effect_observed" -> readSideEffect(root, metadata);
            case "tool_invocation_settled" -> new RunEvent.ToolInvocationSettled(
                    metadata, new ToolUseId(requiredText(root, "toolUseId")),
                    readToolResult(required(root, "result")));
            case "compaction_completed" -> readCompaction(root, metadata);
            case "run_suspended" -> new RunEvent.RunSuspended(
                    metadata, RunSuspensionJsonCodec.fromNode(required(root, "suspension")));
            case "approval_submitted" -> readApproval(root, metadata);
            case "input_answered" -> readInputAnswer(root, metadata);
            case "run_stopped" -> readStopped(root, metadata);
            default -> throw new IOException("unknown run event type: " + root.path("type").asText());
        };
    }

    private static ObjectNode metadata(RunEventMetadata metadata) {
        ObjectNode root = JSON.createObjectNode();
        root.put("schemaVersion", metadata.schemaVersion());
        root.put("runId", metadata.runId().value());
        root.put("sessionId", metadata.sessionId().value());
        root.put("workspaceId", metadata.workspaceId().value());
        root.put("sequence", metadata.sequence());
        root.put("occurredAt", metadata.occurredAt().toString());
        return root;
    }

    private static RunEventMetadata readMetadata(JsonNode root) throws IOException {
        int version = requiredInt(root, "schemaVersion");
        if (version != RunEvent.CURRENT_SCHEMA_VERSION) {
            throw new IOException("unsupported run event schema version: " + version);
        }
        return new RunEventMetadata(
                version,
                RunId.of(requiredText(root, "runId")),
                SessionId.of(requiredText(root, "sessionId")),
                WorkspaceId.of(requiredText(root, "workspaceId")),
                requiredLong(root, "sequence"),
                Instant.parse(requiredText(root, "occurredAt")));
    }

    private static void writeStarted(ObjectNode root, RunEvent.RunStarted event) throws IOException {
        root.put("type", "run_started");
        root.set("initialMessages", messagesNode(event.initialMessages()));
        event.initialCompaction().ifPresent(boundary ->
                root.set("initialCompaction", boundaryNode(boundary)));
    }

    private static RunEvent.RunStarted readStarted(
            JsonNode root, RunEventMetadata metadata) throws IOException {
        Optional<CompactionBoundary> boundary = optional(root, "initialCompaction")
                .map(node -> readBoundaryUnchecked(node));
        return new RunEvent.RunStarted(
                metadata, readMessages(required(root, "initialMessages")), boundary);
    }

    private static void writeSettled(
            ObjectNode root, RunEvent.ToolInvocationSettled event) {
        root.put("type", "tool_invocation_settled");
        root.put("toolUseId", event.toolUseId().value());
        root.set("result", toolResultNode(event.result()));
    }

    private static void writeSideEffect(
            ObjectNode root, RunEvent.ToolSideEffectObserved event) {
        root.put("type", "tool_side_effect_observed");
        root.put("toolUseId", event.toolUseId().value());
        ObjectNode effect = root.putObject("sideEffect");
        switch (event.sideEffect()) {
            case ToolSideEffect.CheckpointedFile checkpointed -> {
                effect.put("kind", "checkpointed_file");
                effect.put("checkpointId", checkpointed.checkpointId().value());
            }
            case ToolSideEffect.NonReversible residual -> {
                effect.put("kind", "non_reversible");
                effect.put("toolName", residual.toolName());
                effect.put("detail", residual.detail());
            }
        }
    }

    private static RunEvent.ToolSideEffectObserved readSideEffect(
            JsonNode root, RunEventMetadata metadata) throws IOException {
        JsonNode node = required(root, "sideEffect");
        ToolSideEffect sideEffect = switch (requiredText(node, "kind")) {
            case "checkpointed_file" -> new ToolSideEffect.CheckpointedFile(
                    CheckpointId.of(requiredText(node, "checkpointId")));
            case "non_reversible" -> new ToolSideEffect.NonReversible(
                    requiredText(node, "toolName"), requiredText(node, "detail"));
            default -> throw new IOException(
                    "unknown tool side effect kind: " + node.path("kind").asText());
        };
        return new RunEvent.ToolSideEffectObserved(
                metadata, new ToolUseId(requiredText(root, "toolUseId")), sideEffect);
    }

    private static void writeCompaction(
            ObjectNode root, RunEvent.CompactionCompleted event) throws IOException {
        root.put("type", "compaction_completed");
        root.set("boundary", boundaryNode(event.boundary()));
        root.set("retainedMessages", messagesNode(event.retainedMessages()));
    }

    private static RunEvent.CompactionCompleted readCompaction(
            JsonNode root, RunEventMetadata metadata) throws IOException {
        return new RunEvent.CompactionCompleted(
                metadata, readBoundary(required(root, "boundary")),
                readMessages(required(root, "retainedMessages")));
    }

    private static void writeApproval(
            ObjectNode root, RunEvent.ApprovalSubmitted event) throws IOException {
        root.put("type", "approval_submitted");
        root.set("suspension", RunSuspensionJsonCodec.toNode(event.suspension()));
        root.put("decision", event.decision().name());
    }

    private static RunEvent.ApprovalSubmitted readApproval(
            JsonNode root, RunEventMetadata metadata) throws IOException {
        RunSuspension suspension = RunSuspensionJsonCodec.fromNode(
                required(root, "suspension"));
        if (!(suspension instanceof RunSuspension.WaitingForApproval approval)) {
            throw new IOException("approval event requires approval suspension");
        }
        return new RunEvent.ApprovalSubmitted(
                metadata, approval,
                ApprovalDecision.valueOf(requiredText(root, "decision")));
    }

    private static void writeInputAnswer(
            ObjectNode root, RunEvent.InputAnswered event) throws IOException {
        root.put("type", "input_answered");
        root.set("suspension", RunSuspensionJsonCodec.toNode(event.suspension()));
        root.put("answer", event.answer().value());
    }

    private static RunEvent.InputAnswered readInputAnswer(
            JsonNode root, RunEventMetadata metadata) throws IOException {
        RunSuspension suspension = RunSuspensionJsonCodec.fromNode(
                required(root, "suspension"));
        if (!(suspension instanceof RunSuspension.WaitingForInput input)) {
            throw new IOException("input answer event requires input suspension");
        }
        return new RunEvent.InputAnswered(
                metadata, input, InputAnswer.of(requiredText(root, "answer")));
    }

    private static void writeStopped(ObjectNode root, RunEvent.RunStopped event) throws IOException {
        root.put("type", "run_stopped");
        root.put("stopReason", event.stopReason().name());
        root.set("finalMessage", messageNode(event.finalMessage()));
        event.structuredOutput().ifPresent(payload ->
                root.set("structuredOutput", JSON.valueToTree(payload)));
        root.set("usage", usageNode(event.usage()));
        root.set("consumption", consumptionNode(event.consumption()));
        event.errorDetail().ifPresent(detail -> root.put("errorDetail", detail));
    }

    private static RunEvent.RunStopped readStopped(
            JsonNode root, RunEventMetadata metadata) throws IOException {
        return new RunEvent.RunStopped(
                metadata,
                StopReason.valueOf(requiredText(root, "stopReason")),
                (AiMessage) readMessage(required(root, "finalMessage")),
                readPayload(root.get("structuredOutput")),
                readUsage(required(root, "usage")),
                readConsumption(required(root, "consumption")),
                optionalText(root, "errorDetail"));
    }

    private static ArrayNode messagesNode(List<ChatMessage> messages) throws IOException {
        ArrayNode array = JSON.createArrayNode();
        for (ChatMessage message : messages) {
            array.add(messageNode(message));
        }
        return array;
    }

    private static JsonNode messageNode(ChatMessage message) throws IOException {
        return JSON.readTree(MessageJsonCodec.toJson(message));
    }

    private static List<ChatMessage> readMessages(JsonNode array) throws IOException {
        if (!array.isArray()) {
            throw new IOException("messages field must be an array");
        }
        List<ChatMessage> messages = new ArrayList<>();
        for (JsonNode node : array) {
            messages.add(readMessage(node));
        }
        return List.copyOf(messages);
    }

    private static ChatMessage readMessage(JsonNode node) throws IOException {
        return MessageJsonCodec.fromJson(node.toString());
    }

    private static ObjectNode boundaryNode(CompactionBoundary boundary) {
        ObjectNode node = JSON.createObjectNode();
        node.put("sourceStartInclusive", boundary.sourceStartInclusive());
        node.put("sourceEndExclusive", boundary.sourceEndExclusive());
        node.put("originalEstimatedTokens", boundary.originalEstimatedTokens());
        node.put("summaryVersion", boundary.summaryVersion());
        node.put("summary", boundary.summary());
        return node;
    }

    private static CompactionBoundary readBoundary(JsonNode node) throws IOException {
        return new CompactionBoundary(
                requiredInt(node, "sourceStartInclusive"),
                requiredInt(node, "sourceEndExclusive"),
                requiredInt(node, "originalEstimatedTokens"),
                requiredInt(node, "summaryVersion"),
                requiredText(node, "summary"));
    }

    private static CompactionBoundary readBoundaryUnchecked(JsonNode node) {
        try {
            return readBoundary(node);
        } catch (IOException failure) {
            throw new IllegalArgumentException(failure);
        }
    }

    private static ObjectNode toolResultNode(ToolResult result) {
        ObjectNode node = JSON.createObjectNode();
        node.put("status", result.status().name());
        node.put("content", result.content());
        ObjectNode metadata = node.putObject("metadata");
        result.metadata().forEach(metadata::put);
        return node;
    }

    private static ToolResult readToolResult(JsonNode node) throws IOException {
        Map<String, String> metadata = new LinkedHashMap<>();
        JsonNode metadataNode = node.get("metadata");
        if (metadataNode != null && metadataNode.isObject()) {
            metadataNode.fields().forEachRemaining(
                    field -> metadata.put(field.getKey(), field.getValue().asText()));
        }
        return ToolResult.of(
                ToolResultStatus.valueOf(requiredText(node, "status")),
                requiredText(node, "content"), metadata);
    }

    private static ObjectNode usageNode(AgentUsage usage) {
        ObjectNode node = JSON.createObjectNode();
        node.put("inputTokens", usage.inputTokens());
        node.put("outputTokens", usage.outputTokens());
        node.put("cacheReadInputTokens", usage.cacheReadInputTokens());
        ArrayNode models = node.putArray("models");
        usage.modelUsage().forEach(model -> models.add(modelUsageNode(model)));
        return node;
    }

    private static AgentUsage readUsage(JsonNode node) throws IOException {
        return new AgentUsage(
                requiredLong(node, "inputTokens"),
                requiredLong(node, "outputTokens"),
                requiredLong(node, "cacheReadInputTokens"),
                readModelUsage(node.get("models")));
    }

    private static ObjectNode modelUsageNode(ModelUsage usage) {
        ObjectNode node = JSON.createObjectNode();
        node.put("provider", usage.model().provider());
        node.put("model", usage.model().model());
        node.put("attempts", usage.attempts());
        node.put("inputTokens", usage.inputTokens());
        node.put("outputTokens", usage.outputTokens());
        node.put("cacheReadInputTokens", usage.cacheReadInputTokens());
        return node;
    }

    private static List<ModelUsage> readModelUsage(JsonNode models) throws IOException {
        if (models == null || models.isNull()) {
            return List.of();
        }
        if (!models.isArray()) {
            throw new IOException("run event usage models must be an array");
        }
        List<ModelUsage> usage = new ArrayList<>();
        for (JsonNode model : models) {
            usage.add(new ModelUsage(
                    new ModelIdentity(requiredText(model, "provider"),
                            requiredText(model, "model")),
                    requiredInt(model, "attempts"),
                    requiredLong(model, "inputTokens"),
                    requiredLong(model, "outputTokens"),
                    requiredLong(model, "cacheReadInputTokens")));
        }
        return List.copyOf(usage);
    }

    private static ObjectNode consumptionNode(BudgetConsumption consumption) {
        ObjectNode node = JSON.createObjectNode();
        node.put("turns", consumption.turns());
        node.put("toolCalls", consumption.toolCalls());
        node.put("inputTokens", consumption.inputTokens());
        node.put("outputTokens", consumption.outputTokens());
        node.put("outputCharacters", consumption.outputCharacters());
        node.put("llmCalls", consumption.llmCalls());
        return node;
    }

    private static BudgetConsumption readConsumption(JsonNode node) throws IOException {
        return new BudgetConsumption(
                requiredInt(node, "turns"),
                requiredInt(node, "toolCalls"),
                requiredLong(node, "inputTokens"),
                requiredLong(node, "outputTokens"),
                requiredLong(node, "outputCharacters"),
                optionalInt(node, "llmCalls"));
    }

    private static int optionalInt(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? 0 : value.asInt();
    }

    private static Optional<Map<String, Object>> readPayload(JsonNode node) {
        if (node == null || node.isNull()) {
            return Optional.empty();
        }
        return Optional.of(Map.copyOf(JSON.convertValue(node, OBJECT_MAP)));
    }

    private static JsonNode required(JsonNode root, String field) throws IOException {
        JsonNode value = root.get(field);
        if (value == null || value.isNull()) {
            throw new IOException("missing run event field: " + field);
        }
        return value;
    }

    private static String requiredText(JsonNode root, String field) throws IOException {
        JsonNode value = required(root, field);
        if (!value.isTextual()) {
            throw new IOException("run event field is not text: " + field);
        }
        return value.asText();
    }

    private static int requiredInt(JsonNode root, String field) throws IOException {
        JsonNode value = required(root, field);
        if (!value.canConvertToInt()) {
            throw new IOException("run event field is not int: " + field);
        }
        return value.intValue();
    }

    private static long requiredLong(JsonNode root, String field) throws IOException {
        JsonNode value = required(root, field);
        if (!value.canConvertToLong()) {
            throw new IOException("run event field is not long: " + field);
        }
        return value.longValue();
    }

    private static Optional<JsonNode> optional(JsonNode root, String field) {
        JsonNode value = root.get(field);
        return value == null || value.isNull() ? Optional.empty() : Optional.of(value);
    }

    private static Optional<String> optionalText(JsonNode root, String field) {
        return optional(root, field).map(JsonNode::asText).filter(value -> !value.isBlank());
    }
}
