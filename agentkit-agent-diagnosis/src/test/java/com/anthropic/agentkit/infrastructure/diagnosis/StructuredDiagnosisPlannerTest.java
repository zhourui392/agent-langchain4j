package com.anthropic.agentkit.infrastructure.diagnosis;

import com.anthropic.agentkit.domain.diagnosis.DiagnosisCase;
import com.anthropic.agentkit.domain.diagnosis.DiagnosisPlan;
import com.anthropic.agentkit.domain.diagnosis.StepStatus;
import com.anthropic.agentkit.domain.message.AiMessage;
import com.anthropic.agentkit.domain.tool.ToolUseId;
import com.anthropic.agentkit.domain.tool.ToolUseRequest;
import com.anthropic.agentkit.testsupport.StubLlmClient;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class StructuredDiagnosisPlannerTest {

    @Test
    void createsPlanFromUpdatePlanToolUse() {
        StubLlmClient llm = new StubLlmClient()
                .enqueue(new AiMessage("", List.of(new ToolUseRequest(
                        new ToolUseId("plan-1"), "update_plan", planJson()))))
                .enqueue(AiMessage.text("planned"));
        StructuredDiagnosisPlanner planner = new StructuredDiagnosisPlanner(llm);

        DiagnosisPlan plan = planner.createPlan(DiagnosisCase.open("case-1", "订单失败"));

        assertThat(plan.problemStatement()).isEqualTo("订单失败");
        assertThat(plan.hypotheses()).singleElement()
                .satisfies(hypothesis -> assertThat(hypothesis.id()).isEqualTo("H1"));
        assertThat(plan.steps()).singleElement().satisfies(step -> {
            assertThat(step.allowedTools()).containsExactly("LogQuery");
            assertThat(step.status()).isEqualTo(StepStatus.PENDING);
        });
        assertThat(plan.missingInputs()).containsExactly("timeWindow");
        assertThat(llm.capturedRequests().get(0).tools())
                .extracting(com.anthropic.agentkit.domain.port.ToolSpec::name)
                .contains("update_plan");
    }

    private static String planJson() {
        return """
                {
                  "problemStatement": "订单失败",
                  "hypotheses": [
                    {"id": "H1", "statement": "入口服务报错", "confidence": 0.4}
                  ],
                  "steps": [
                    {
                      "id": "S1",
                      "goal": "查日志",
                      "hypothesisId": "H1",
                      "allowedTools": ["LogQuery"],
                      "status": "PENDING"
                    }
                  ],
                  "missingInputs": ["timeWindow"]
                }
                """;
    }
}
