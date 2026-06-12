package com.anthropic.cclc.infrastructure.streamjson;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Assembles single-line Claude {@code stream-json} events that agent-web's
 * consumer parses verbatim. See {@code docs/samples/README.md} for the binding
 * field contract. Pure function object, no side effects.
 *
 * <p>{@link com.fasterxml.jackson.databind.JsonNode#toString()} renders compact
 * JSON with correct escaping, so every method returns exactly one NDJSON line.
 *
 * @author zhourui(V33215020)
 * @since 2026-06-08
 */
public final class ClaudeStreamJsonWriter {

    private final JsonNodeFactory nodes = JsonNodeFactory.instance;
    private final ObjectMapper mapper = new ObjectMapper();

    /** Token accounting echoed on the result event (agent-web persists it). */
    public record Usage(long inputTokens, long outputTokens, long cacheReadInputTokens) {
    }

    /** Tool-use block carried by a consolidated assistant message line. */
    public record AssistantToolUse(String id, String name, String inputJson) {
        public AssistantToolUse {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(inputJson, "inputJson");
        }
    }

    public String systemInit(String sessionId, String cwd) {
        ObjectNode root = nodes.objectNode();
        root.put("type", "system");
        root.put("subtype", "init");
        root.put("session_id", sessionId);
        root.put("cwd", cwd);
        return root.toString();
    }

    public String textDelta(String text) {
        ObjectNode delta = nodes.objectNode();
        delta.put("type", "text_delta");
        delta.put("text", text);
        return streamEvent("content_block_delta", "delta", delta);
    }

    public String toolUseStart(String id, String name) {
        ObjectNode block = nodes.objectNode();
        block.put("type", "tool_use");
        block.put("id", id);
        block.put("name", name);
        return streamEvent("content_block_start", "content_block", block);
    }

    public String inputJsonDelta(String partialJson) {
        ObjectNode delta = nodes.objectNode();
        delta.put("type", "input_json_delta");
        delta.put("partial_json", partialJson);
        return streamEvent("content_block_delta", "delta", delta);
    }

    public String contentBlockStop() {
        return streamEvent("content_block_stop", null, null);
    }

    public String toolResult(String toolUseId, String content) {
        ObjectNode block = nodes.objectNode();
        block.put("type", "tool_result");
        block.put("tool_use_id", toolUseId);
        block.put("content", content);
        ArrayNode blocks = nodes.arrayNode();
        blocks.add(block);
        ObjectNode message = nodes.objectNode();
        message.set("content", blocks);
        ObjectNode root = nodes.objectNode();
        root.put("type", "user");
        root.set("message", message);
        return root.toString();
    }

    public String assistantMessage(String text, List<AssistantToolUse> toolUses) {
        ArrayNode blocks = nodes.arrayNode();
        if (text != null && !text.isEmpty()) {
            blocks.add(textBlock(text));
        }
        for (AssistantToolUse toolUse : safeToolUses(toolUses)) {
            blocks.add(toolUseBlock(toolUse));
        }
        ObjectNode message = nodes.objectNode();
        message.set("content", blocks);
        ObjectNode root = nodes.objectNode();
        root.put("type", "assistant");
        root.set("message", message);
        return root.toString();
    }

    public String result(String finalText, String sessionId) {
        return successResult(finalText, sessionId).toString();
    }

    public String result(String finalText, String sessionId, Usage usage) {
        ObjectNode root = successResult(finalText, sessionId);
        root.set("usage", usageNode(usage));
        return root.toString();
    }

    public String errorResult(String message, String sessionId) {
        ObjectNode root = resultEvent("error_during_execution", message, sessionId);
        root.put("is_error", true);
        return root.toString();
    }

    public String extensionEvent(String type, Map<String, Object> payload) {
        ObjectNode root = nodes.objectNode();
        root.put("type", type);
        root.set("payload", mapper.valueToTree(payload));
        return root.toString();
    }

    private ObjectNode successResult(String finalText, String sessionId) {
        return resultEvent("success", finalText, sessionId);
    }

    private ObjectNode resultEvent(String subtype, String text, String sessionId) {
        ObjectNode root = nodes.objectNode();
        root.put("type", "result");
        root.put("subtype", subtype);
        root.put("result", text);
        root.put("session_id", sessionId);
        return root;
    }

    private ObjectNode usageNode(Usage usage) {
        ObjectNode node = nodes.objectNode();
        node.put("input_tokens", usage.inputTokens());
        node.put("output_tokens", usage.outputTokens());
        node.put("cache_read_input_tokens", usage.cacheReadInputTokens());
        return node;
    }

    private ObjectNode textBlock(String text) {
        ObjectNode block = nodes.objectNode();
        block.put("type", "text");
        block.put("text", text);
        return block;
    }

    private ObjectNode toolUseBlock(AssistantToolUse toolUse) {
        ObjectNode block = nodes.objectNode();
        block.put("type", "tool_use");
        block.put("id", toolUse.id());
        block.put("name", toolUse.name());
        block.set("input", inputNode(toolUse.inputJson()));
        return block;
    }

    private JsonNode inputNode(String inputJson) {
        try {
            return mapper.readTree(inputJson);
        } catch (IOException ex) {
            throw new UncheckedIOException("invalid tool input json", ex);
        }
    }

    private static List<AssistantToolUse> safeToolUses(List<AssistantToolUse> toolUses) {
        if (toolUses == null) {
            return List.of();
        }
        return toolUses;
    }

    private String streamEvent(String eventType, String childKey, ObjectNode child) {
        ObjectNode event = nodes.objectNode();
        event.put("type", eventType);
        if (childKey != null) {
            event.set(childKey, child);
        }
        ObjectNode root = nodes.objectNode();
        root.put("type", "stream_event");
        root.set("event", event);
        return root.toString();
    }
}
