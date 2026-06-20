package com.anthropic.agentkit.infrastructure.coding;

import com.anthropic.agentkit.domain.coding.CodingPlan;
import com.anthropic.agentkit.domain.coding.CodingTask;
import com.anthropic.agentkit.domain.coding.TaskItem;
import com.anthropic.agentkit.domain.coding.TaskItemStatus;
import com.anthropic.agentkit.domain.message.AiMessage;
import com.anthropic.agentkit.domain.port.ToolSpec;
import com.anthropic.agentkit.domain.tool.ToolUseId;
import com.anthropic.agentkit.domain.tool.ToolUseRequest;
import com.anthropic.agentkit.testsupport.StubLlmClient;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class StructuredCodingPlannerTest {

    @Test
    void createsPlanFromUpdatePlanToolUse() {
        StubLlmClient llm = new StubLlmClient()
                .enqueue(new AiMessage("", List.of(new ToolUseRequest(
                        new ToolUseId("plan-1"), "update_plan", planJson()))))
                .enqueue(AiMessage.text("planned"));
        StructuredCodingPlanner planner = new StructuredCodingPlanner(llm);

        CodingPlan plan = planner.createPlan(CodingTask.open("task-1", "Add login page"));

        assertThat(plan.problemStatement()).isEqualTo("Add login page");
        assertThat(plan.tasks()).singleElement().satisfies(task -> {
            assertThat(task.id()).isEqualTo("s-1");
            assertThat(task.goal()).isEqualTo("write controller");
            assertThat(task.files()).containsExactly("src/Login.java");
            assertThat(task.status()).isEqualTo(TaskItemStatus.PENDING);
        });
        assertThat(llm.capturedRequests().get(0).tools())
                .extracting(ToolSpec::name)
                .contains("update_plan");
    }

    @Test
    void defaultsMissingStatusToPending() {
        StubLlmClient llm = new StubLlmClient()
                .enqueue(new AiMessage("", List.of(new ToolUseRequest(
                        new ToolUseId("plan-1"), "update_plan",
                        "{\"problemStatement\":\"p\",\"tasks\":[{\"id\":\"t\",\"goal\":\"g\"}]}"))))
                .enqueue(AiMessage.text("done"));
        StructuredCodingPlanner planner = new StructuredCodingPlanner(llm);

        CodingPlan plan = planner.createPlan(CodingTask.open("task-1", "p"));

        assertThat(plan.tasks()).singleElement()
                .extracting(TaskItem::status)
                .isEqualTo(TaskItemStatus.PENDING);
    }

    @Test
    void toleratesAbsentTasksAsEmptyList() {
        StubLlmClient llm = new StubLlmClient()
                .enqueue(new AiMessage("", List.of(new ToolUseRequest(
                        new ToolUseId("plan-1"), "update_plan",
                        "{\"problemStatement\":\"trivial\"}"))))
                .enqueue(AiMessage.text("done"));
        StructuredCodingPlanner planner = new StructuredCodingPlanner(llm);

        CodingPlan plan = planner.createPlan(CodingTask.open("task-1", "trivial"));

        assertThat(plan.tasks()).isEmpty();
    }

    private static String planJson() {
        return """
                {
                  "problemStatement": "Add login page",
                  "tasks": [
                    {
                      "id": "s-1",
                      "goal": "write controller",
                      "files": ["src/Login.java"],
                      "status": "PENDING"
                    }
                  ]
                }
                """;
    }
}
