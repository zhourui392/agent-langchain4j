package com.anthropic.cclc.interfaces.engine;

import com.anthropic.cclc.domain.message.AiMessage;
import com.anthropic.cclc.domain.port.LlmClient;
import com.anthropic.cclc.domain.tool.ToolRegistry;
import com.anthropic.cclc.infrastructure.streamjson.ClaudeStreamJsonListener;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class UsageReportingTest {

    private final List<String> lines = new ArrayList<>();
    private final ClaudeStreamJsonListener listener =
            new ClaudeStreamJsonListener("s-1", "/w", lines::add);
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void resultEventCarriesUsage() {
        listener.onUsage(10, 20, 5);
        listener.onTurnComplete(AiMessage.text("done"));

        JsonNode usage = resultUsage(lines);
        assertThat(usage.get("input_tokens").asInt()).isEqualTo(10);
        assertThat(usage.get("output_tokens").asInt()).isEqualTo(20);
        assertThat(usage.get("cache_read_input_tokens").asInt()).isEqualTo(5);
    }

    @Test
    void aggregatesUsageAcrossTurns() {
        listener.onUsage(10, 20, 5);
        listener.onUsage(1, 2, 3);
        listener.onTurnComplete(AiMessage.text("done"));

        JsonNode usage = resultUsage(lines);
        assertThat(usage.get("input_tokens").asInt()).isEqualTo(11);
        assertThat(usage.get("output_tokens").asInt()).isEqualTo(22);
        assertThat(usage.get("cache_read_input_tokens").asInt()).isEqualTo(8);
    }

    @Test
    void engineForwardsLlmUsageToResult() {
        LlmClient llm = (request, handler) -> {
            handler.onUsage(7, 3, 2);
            handler.onComplete(AiMessage.text("ok"));
        };
        List<String> out = new ArrayList<>();
        AtomicInteger exit = new AtomicInteger();
        DiagnoseEngine engine = new DefaultDiagnoseEngine(llm, new ToolRegistry());

        engine.runStream(RunRequest.builder()
                .workingDir(".").userMessage("hi").sessionId("s-2").build(), out::add, exit::set);

        JsonNode usage = resultUsage(out);
        assertThat(usage.get("input_tokens").asInt()).isEqualTo(7);
        assertThat(usage.get("output_tokens").asInt()).isEqualTo(3);
        assertThat(usage.get("cache_read_input_tokens").asInt()).isEqualTo(2);
    }

    private JsonNode resultUsage(List<String> emitted) {
        return emitted.stream()
                .map(this::parse)
                .filter(node -> node.path("type").asText().equals("result"))
                .map(node -> node.get("usage"))
                .findFirst()
                .orElseThrow();
    }

    private JsonNode parse(String line) {
        try {
            return mapper.readTree(line);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
