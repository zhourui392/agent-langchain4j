package com.anthropic.agentkit.interfaces.engine;

import com.anthropic.agentkit.domain.message.AiMessage;
import com.anthropic.agentkit.domain.tool.ToolResult;
import com.anthropic.agentkit.domain.tool.ToolUseId;
import com.anthropic.agentkit.domain.tool.ToolUseRequest;
import com.anthropic.agentkit.infrastructure.streamjson.ClaudeStreamJsonListener;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;

class ClaudeStreamJsonListenerTest {

    private final List<String> lines = new ArrayList<>();
    private final ClaudeStreamJsonListener listener =
            new ClaudeStreamJsonListener("sid-1", "/work", lines::add);
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void emitsSystemInitOnce() {
        listener.onLlmRequestStart();
        listener.onAssistantTextDelta("hi");
        listener.onLlmRequestStart();

        long initCount = lines.stream().filter(l -> typeOf(l).equals("system")).count();
        assertThat(initCount).isEqualTo(1);
        assertThat(typeOf(lines.get(0))).isEqualTo("system");
    }

    @Test
    void streamsTextDeltas() {
        listener.onAssistantTextDelta("Hello ");
        listener.onAssistantTextDelta("world");

        List<String> texts = new ArrayList<>();
        for (String l : lines) {
            JsonNode n = parse(l);
            if (isTextDelta(n)) {
                texts.add(n.get("event").get("delta").get("text").asText());
            }
        }
        assertThat(texts).containsExactly("Hello ", "world");
    }

    @Test
    void emitsToolUseThenResult() {
        ToolUseRequest req = new ToolUseRequest(new ToolUseId("tu-1"), "LogQuery", "{\"q\":\"x\"}");

        listener.onToolUseStart(req);
        listener.onTurnComplete(new AiMessage("looking", List.of(req)));
        listener.onToolUseEnd(req, ToolResult.ok("found 3"), 12L);

        JsonNode start = firstWhere(n -> n.path("event").path("type").asText().equals("content_block_start"));
        assertThat(start.get("event").get("content_block").get("name").asText()).isEqualTo("LogQuery");
        JsonNode input = firstWhere(this::isInputJsonDelta);
        assertThat(input.get("event").get("delta").get("partial_json").asText()).isEqualTo("{\"q\":\"x\"}");
        JsonNode user = firstWhere(n -> n.path("type").asText().equals("user"));
        assertThat(user.get("message").get("content").get(0).get("content").asText()).isEqualTo("found 3");
        assertThat(user.get("message").get("content").get(0).get("tool_use_id").asText()).isEqualTo("tu-1");
    }

    @Test
    void emitsAssistantLineAfterContentBlocks() {
        ToolUseRequest req = new ToolUseRequest(new ToolUseId("tu-1"), "LogQuery", "{\"q\":\"x\"}");

        listener.onAssistantTextDelta("look");
        listener.onAssistantTextDelta("ing");
        listener.onToolUseStart(req);
        listener.onTurnComplete(new AiMessage("looking", List.of(req)));
        listener.onToolUseEnd(req, ToolResult.ok("found 3"), 12L);

        List<String> types = lines.stream().map(this::typeOf).toList();
        int assistantIndex = types.indexOf("assistant");
        int userIndex = types.indexOf("user");
        assertThat(assistantIndex).isGreaterThan(lastStreamEventIndex());
        assertThat(assistantIndex).isLessThan(userIndex);

        JsonNode assistant = firstWhere(n -> n.path("type").asText().equals("assistant"));
        assertThat(assistant.path("message").path("content").get(0).path("text").asText()).isEqualTo("looking");
        assertThat(assistant.path("message").path("content").get(1).path("input").path("q").asText()).isEqualTo("x");
    }

    @Test
    void emitsResultOnTurnComplete() {
        listener.onTurnComplete(AiMessage.text("final answer"));

        JsonNode result = firstWhere(n -> n.path("type").asText().equals("result"));
        assertThat(result.get("subtype").asText()).isEqualTo("success");
        assertThat(result.get("result").asText()).isEqualTo("final answer");
        assertThat(result.get("session_id").asText()).isEqualTo("sid-1");
    }

    @Test
    void emitsErrorAsResultFailure() {
        listener.onError(new IllegalStateException("boom"));

        JsonNode result = firstWhere(n -> n.path("type").asText().equals("result"));
        assertThat(result.get("subtype").asText()).isEqualTo("error_during_execution");
        assertThat(result.get("result").asText()).contains("boom");
        assertThat(result.get("is_error").asBoolean()).isTrue();
    }

    @Test
    void emitsExtensionEventsThroughKernelHook() {
        listener.emit("diagnosis_plan", Map.of("phase", "collect"));

        JsonNode event = firstWhere(n -> n.path("type").asText().equals("diagnosis_plan"));
        assertThat(event.get("payload").get("phase").asText()).isEqualTo("collect");
    }

    private String typeOf(String line) {
        return parse(line).path("type").asText();
    }

    private boolean isTextDelta(JsonNode n) {
        return n.path("type").asText().equals("stream_event")
                && n.path("event").path("delta").path("type").asText().equals("text_delta");
    }

    private boolean isInputJsonDelta(JsonNode n) {
        return n.path("type").asText().equals("stream_event")
                && n.path("event").path("delta").path("type").asText().equals("input_json_delta");
    }

    private JsonNode firstWhere(Predicate<JsonNode> pred) {
        return lines.stream().map(this::parse).filter(pred).findFirst().orElseThrow();
    }

    private int lastStreamEventIndex() {
        int index = -1;
        for (int i = 0; i < lines.size(); i++) {
            if (typeOf(lines.get(i)).equals("stream_event")) {
                index = i;
            }
        }
        return index;
    }

    private JsonNode parse(String line) {
        try {
            return mapper.readTree(line);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
