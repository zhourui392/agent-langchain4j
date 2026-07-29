package com.anthropic.agentkit.infrastructure.tools;

import com.anthropic.agentkit.domain.tool.ExecutionContext;
import com.anthropic.agentkit.domain.tool.ToolArguments;
import com.anthropic.agentkit.domain.tool.ToolResult;
import org.junit.jupiter.api.Test;

import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class StructuredOutputToolTest {

    private static final String SCHEMA = """
            {
              "type": "object",
              "properties": {
                "problemStatement": {"type": "string"},
                "missingInputs": {"type": "array"}
              },
              "required": ["problemStatement"]
            }
            """;

    private final List<Map<String, Object>> accepted = new ArrayList<>();
    private final StructuredOutputTool tool = new StructuredOutputTool(
            "update_plan", "Submit a diagnosis plan", SCHEMA, accepted::add);

    @Test
    void exposesRegisteredToolContract() {
        assertThat(tool.name()).isEqualTo("update_plan");
        assertThat(tool.description()).contains("diagnosis plan");
        assertThat(tool.inputSchema()).isEqualTo(SCHEMA);
        assertThat(tool.isReadOnly()).isTrue();
    }

    @Test
    void acceptsPayloadMatchingRequiredFields() {
        ToolResult result = tool.execute(ToolArguments.of(Map.of(
                "problemStatement", "order failed",
                "missingInputs", List.of("timeWindow"))), context());

        assertThat(result.success()).isTrue();
        assertThat(accepted).hasSize(1);
        assertThat(accepted.get(0)).containsEntry("problemStatement", "order failed");
    }

    @Test
    void rejectsPayloadMissingRequiredFields() {
        ToolResult result = tool.execute(ToolArguments.of(Map.of("missingInputs", List.of())), context());

        assertThat(result.success()).isFalse();
        assertThat(result.content()).contains("problemStatement");
        assertThat(accepted).isEmpty();
    }

    @Test
    void rejectsWrongTypeAndUnknownProperty() {
        String strictSchema = """
                {"type":"object","properties":{
                  "decision":{"type":"string","enum":["ACCEPT","REJECT"]},
                  "details":{"type":"object","properties":{"count":{"type":"integer"}},
                             "required":["count"],"additionalProperties":false}
                },"required":["decision","details"],"additionalProperties":false}
                """;
        StructuredOutputTool strict = new StructuredOutputTool(
                "submit_review", "Submit review", strictSchema, accepted::add);

        ToolResult wrongType = strict.execute(ToolArguments.of(Map.of(
                "decision", "ACCEPT", "details", Map.of("count", "one"))), context());
        ToolResult unknownProperty = strict.execute(ToolArguments.of(Map.of(
                "decision", "ACCEPT", "details", Map.of("count", 1), "extra", true)), context());

        assertThat(wrongType.success()).isFalse();
        assertThat(wrongType.content()).contains("details.count", "integer");
        assertThat(unknownProperty.success()).isFalse();
        assertThat(unknownProperty.content()).contains("extra", "not allowed");
        assertThat(accepted).isEmpty();
    }

    private static ExecutionContext context() {
        return ExecutionContext.at(Paths.get(System.getProperty("user.dir")));
    }
}
