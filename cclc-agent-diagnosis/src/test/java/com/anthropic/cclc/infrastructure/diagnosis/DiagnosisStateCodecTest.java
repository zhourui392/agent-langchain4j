package com.anthropic.cclc.infrastructure.diagnosis;

import com.anthropic.cclc.domain.diagnosis.DiagnosisCase;
import com.anthropic.cclc.domain.diagnosis.DiagnosisPlan;
import com.anthropic.cclc.domain.diagnosis.DiagnosisStep;
import com.anthropic.cclc.domain.diagnosis.Hypothesis;
import com.anthropic.cclc.domain.diagnosis.StepStatus;
import com.anthropic.cclc.domain.tool.ToolResult;
import com.anthropic.cclc.domain.tool.ToolUseId;
import com.anthropic.cclc.domain.tool.ToolUseRequest;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DiagnosisStateCodecTest {

    private final DiagnosisStateCodec codec = new DiagnosisStateCodec();

    @Test
    void roundTripsDiagnosisStateSnapshot() {
        DiagnosisCase diagnosisCase = DiagnosisCase.open("case-1", "订单失败");
        diagnosisCase.adoptPlan(new DiagnosisPlan(
                "订单失败",
                List.of(Hypothesis.open("H1", "入口服务报错", 0.4)),
                List.of(new DiagnosisStep("S1", "查日志", "H1", List.of("LogQuery"), StepStatus.RUNNING, ""))));
        diagnosisCase.recordToolEvidence(
                new ToolUseRequest(new ToolUseId("tu-1"), "LogQuery", "{}"),
                ToolResult.ok("inventory fail"));

        String snapshot = codec.encode(diagnosisCase);
        DiagnosisCase restored = codec.decode(snapshot).orElseThrow();

        assertThat(restored.caseId()).isEqualTo("case-1");
        assertThat(restored.plan().problemStatement()).isEqualTo("订单失败");
        assertThat(restored.ledger().all()).hasSize(1);
        assertThat(restored.ledger().all().get(0).toolUseId()).isEqualTo("tu-1");
    }

    @Test
    void returnsEmptyForInvalidSnapshot() {
        assertThat(codec.decode("{broken")).isEmpty();
        assertThat(codec.decode("{\"schemaVersion\":999}")).isEmpty();
    }
}
