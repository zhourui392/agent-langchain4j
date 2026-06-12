package com.anthropic.cclc.interfaces.engine;

import com.anthropic.cclc.infrastructure.streamjson.ClaudeStreamJsonWriter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ClaudeStreamJsonWriterTest {

    private final ClaudeStreamJsonWriter writer = new ClaudeStreamJsonWriter();
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void writesSystemInit() throws Exception {
        JsonNode n = parse(writer.systemInit("sid-1", "/work/dir"));

        assertThat(n.get("type").asText()).isEqualTo("system");
        assertThat(n.get("subtype").asText()).isEqualTo("init");
        assertThat(n.get("session_id").asText()).isEqualTo("sid-1");
        assertThat(n.get("cwd").asText()).isEqualTo("/work/dir");
    }

    @Test
    void writesTextDelta() throws Exception {
        JsonNode n = parse(writer.textDelta("hello \"world\"\nline2"));

        assertThat(n.get("type").asText()).isEqualTo("stream_event");
        JsonNode evt = n.get("event");
        assertThat(evt.get("type").asText()).isEqualTo("content_block_delta");
        JsonNode delta = evt.get("delta");
        assertThat(delta.get("type").asText()).isEqualTo("text_delta");
        assertThat(delta.get("text").asText()).isEqualTo("hello \"world\"\nline2");
    }

    @Test
    void writesToolUseStartAndInputDelta() throws Exception {
        JsonNode start = parse(writer.toolUseStart("tu-1", "LogQuery"));
        assertThat(start.get("event").get("type").asText()).isEqualTo("content_block_start");
        JsonNode cb = start.get("event").get("content_block");
        assertThat(cb.get("type").asText()).isEqualTo("tool_use");
        assertThat(cb.get("id").asText()).isEqualTo("tu-1");
        assertThat(cb.get("name").asText()).isEqualTo("LogQuery");

        JsonNode delta = parse(writer.inputJsonDelta("{\"q\":\"err\"}"));
        JsonNode d = delta.get("event").get("delta");
        assertThat(d.get("type").asText()).isEqualTo("input_json_delta");
        assertThat(d.get("partial_json").asText()).isEqualTo("{\"q\":\"err\"}");

        JsonNode stop = parse(writer.contentBlockStop());
        assertThat(stop.get("event").get("type").asText()).isEqualTo("content_block_stop");
    }

    @Test
    void writesToolResult() throws Exception {
        JsonNode n = parse(writer.toolResult("tu-1", "found 3 errors"));

        assertThat(n.get("type").asText()).isEqualTo("user");
        JsonNode block = n.get("message").get("content").get(0);
        assertThat(block.get("type").asText()).isEqualTo("tool_result");
        assertThat(block.get("tool_use_id").asText()).isEqualTo("tu-1");
        assertThat(block.get("content").asText()).isEqualTo("found 3 errors");
    }

    @Test
    void writesConsolidatedAssistantMessage() throws Exception {
        JsonNode n = parse(writer.assistantMessage("looking",
                List.of(new ClaudeStreamJsonWriter.AssistantToolUse(
                        "tu-1", "LogQuery", "{\"q\":\"err\"}"))));

        assertThat(n.get("type").asText()).isEqualTo("assistant");
        JsonNode text = n.get("message").get("content").get(0);
        assertThat(text.get("type").asText()).isEqualTo("text");
        assertThat(text.get("text").asText()).isEqualTo("looking");

        JsonNode toolUse = n.get("message").get("content").get(1);
        assertThat(toolUse.get("type").asText()).isEqualTo("tool_use");
        assertThat(toolUse.get("id").asText()).isEqualTo("tu-1");
        assertThat(toolUse.get("name").asText()).isEqualTo("LogQuery");
        assertThat(toolUse.get("input").get("q").asText()).isEqualTo("err");
    }

    @Test
    void writesResultSuccess() throws Exception {
        JsonNode n = parse(writer.result("done", "sid-1"));

        assertThat(n.get("type").asText()).isEqualTo("result");
        assertThat(n.get("subtype").asText()).isEqualTo("success");
        assertThat(n.get("result").asText()).isEqualTo("done");
        assertThat(n.get("session_id").asText()).isEqualTo("sid-1");
        assertThat(n.has("usage")).isFalse();
    }

    @Test
    void writesResultWithUsage() throws Exception {
        JsonNode n = parse(writer.result("done", "sid-1",
                new ClaudeStreamJsonWriter.Usage(10, 20, 5)));

        JsonNode usage = n.get("usage");
        assertThat(usage.get("input_tokens").asLong()).isEqualTo(10);
        assertThat(usage.get("output_tokens").asLong()).isEqualTo(20);
        assertThat(usage.get("cache_read_input_tokens").asLong()).isEqualTo(5);
    }

    @Test
    void writesErrorResult() throws Exception {
        JsonNode n = parse(writer.errorResult("boom", "sid-1"));

        assertThat(n.get("type").asText()).isEqualTo("result");
        assertThat(n.get("subtype").asText()).isEqualTo("error_during_execution");
        assertThat(n.get("result").asText()).isEqualTo("boom");
        assertThat(n.get("session_id").asText()).isEqualTo("sid-1");
        assertThat(n.get("is_error").asBoolean()).isTrue();
    }

    @Test
    void writesExtensionEvent() throws Exception {
        JsonNode n = parse(writer.extensionEvent("diagnosis_evidence", Map.of("count", 3)));

        assertThat(n.get("type").asText()).isEqualTo("diagnosis_evidence");
        assertThat(n.get("payload").get("count").asInt()).isEqualTo(3);
    }

    private JsonNode parse(String line) throws Exception {
        assertThat(line).doesNotContain("\n");
        return mapper.readTree(line);
    }
}
