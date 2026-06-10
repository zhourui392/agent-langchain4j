package com.anthropic.cclc.infrastructure.diagnosis;

import com.anthropic.cclc.domain.diagnosis.DiagnosisCase;
import com.anthropic.cclc.domain.diagnosis.DiagnosisPlan;
import com.anthropic.cclc.domain.diagnosis.DiagnosisReport;
import com.anthropic.cclc.domain.diagnosis.DiagnosisStep;
import com.anthropic.cclc.domain.diagnosis.Hypothesis;
import com.anthropic.cclc.domain.diagnosis.StepStatus;
import com.anthropic.cclc.domain.message.AiMessage;
import com.anthropic.cclc.domain.tool.ToolResult;
import com.anthropic.cclc.domain.tool.ToolUseId;
import com.anthropic.cclc.domain.tool.ToolUseRequest;
import com.anthropic.cclc.testsupport.StubLlmClient;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StructuredDiagnosisReporterTest {

    @Test
    void createsValidatedReportFromSubmitReportToolUse() {
        StubLlmClient llm = new StubLlmClient()
                .enqueue(new AiMessage("", List.of(new ToolUseRequest(
                        new ToolUseId("report-1"), "submit_report", reportJson("E1")))))
                .enqueue(AiMessage.text("reported"));
        DiagnosisCase diagnosisCase = caseWithToolEvidence();

        DiagnosisReport report = new StructuredDiagnosisReporter(llm).report(diagnosisCase);

        assertThat(report.summary()).isEqualTo("库存不足");
        assertThat(report.rootCauseCandidates()).singleElement()
                .satisfies(candidate -> assertThat(candidate.evidenceIds()).containsExactly("E1"));
        assertThat(llm.capturedRequests().get(0).tools())
                .extracting(com.anthropic.cclc.domain.port.ToolSpec::name)
                .contains("submit_report");
    }

    @Test
    void rejectsReportWithMissingEvidenceReference() {
        StubLlmClient llm = new StubLlmClient()
                .enqueue(new AiMessage("", List.of(new ToolUseRequest(
                        new ToolUseId("report-1"), "submit_report", reportJson("missing")))))
                .enqueue(AiMessage.text("reported"));

        assertThatThrownBy(() -> new StructuredDiagnosisReporter(llm).report(caseWithToolEvidence()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("missing evidence");
    }

    private static DiagnosisCase caseWithToolEvidence() {
        DiagnosisCase diagnosisCase = DiagnosisCase.open("case-1", "订单失败");
        diagnosisCase.adoptPlan(new DiagnosisPlan(
                "订单失败",
                List.of(Hypothesis.open("H1", "入口服务报错", 0.4)),
                List.of(new DiagnosisStep("S1", "查日志", "H1",
                        List.of("LogQuery"), StepStatus.RUNNING, ""))));
        diagnosisCase.recordToolEvidence(
                new ToolUseRequest(new ToolUseId("tu-1"), "LogQuery", "{}"),
                ToolResult.ok("inventory fail"));
        return diagnosisCase;
    }

    private static String reportJson(String evidenceId) {
        return """
                {
                  "summary": "库存不足",
                  "rootCauseCandidates": [
                    {
                      "hypothesisId": "H1",
                      "summary": "库存不足",
                      "evidenceIds": ["%s"],
                      "confidence": 0.8,
                      "confirmed": true
                    }
                  ],
                  "keyEvidenceIds": ["%s"],
                  "recommendedActions": ["人工确认库存配置"],
                  "confidence": 0.7,
                  "needHumanCheck": true
                }
                """.formatted(evidenceId, evidenceId);
    }
}
