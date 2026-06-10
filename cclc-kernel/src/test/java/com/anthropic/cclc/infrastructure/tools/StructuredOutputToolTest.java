package com.anthropic.cclc.infrastructure.tools;

import com.anthropic.cclc.domain.tool.ExecutionContext;
import com.anthropic.cclc.domain.tool.ToolArguments;
import com.anthropic.cclc.domain.tool.ToolResult;
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

    private static ExecutionContext context() {
        return ExecutionContext.at(Paths.get(System.getProperty("user.dir")));
    }
}
