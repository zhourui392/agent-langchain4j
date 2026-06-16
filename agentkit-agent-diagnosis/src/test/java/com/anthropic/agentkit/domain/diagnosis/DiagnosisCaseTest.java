package com.anthropic.agentkit.domain.diagnosis;

import com.anthropic.agentkit.domain.tool.ToolResult;
import com.anthropic.agentkit.domain.tool.ToolUseId;
import com.anthropic.agentkit.domain.tool.ToolUseRequest;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DiagnosisCaseTest {

    @Test
    void adoptsValidPlanAndMovesToRunning() {
        DiagnosisCase diagnosisCase = DiagnosisCase.open("case-1", "订单失败");

        diagnosisCase.adoptPlan(plan(StepStatus.PENDING));

        assertThat(diagnosisCase.status()).isEqualTo(DiagnosisStatus.RUNNING);
        assertThat(diagnosisCase.plan()).isNotNull();
    }

    @Test
    void confirmedRootCauseRequiresNonModelEvidence() {
        DiagnosisCase diagnosisCase = DiagnosisCase.open("case-1", "订单失败");
        diagnosisCase.adoptPlan(plan(StepStatus.RUNNING));
        diagnosisCase.recordModelInference("怀疑库存不足");

        assertThat(diagnosisCase.canConfirmRootCause("H1")).isFalse();

        diagnosisCase.recordToolEvidence(
                new ToolUseRequest(new ToolUseId("tu-1"), "LogQuery", "{}"),
                ToolResult.ok("inventory fail"));

        assertThat(diagnosisCase.canConfirmRootCause("H1")).isTrue();
    }

    @Test
    void recordsOffPlanMetadataWhenToolDoesNotMatchCurrentPlan() {
        DiagnosisCase diagnosisCase = DiagnosisCase.open("case-1", "订单失败");
        diagnosisCase.adoptPlan(plan(StepStatus.RUNNING));

        Evidence evidence = diagnosisCase.recordToolEvidence(
                new ToolUseRequest(new ToolUseId("tu-1"), "RedisRead", "{}"),
                ToolResult.ok("cache ok"));

        assertThat(evidence.metadata()).containsEntry("offPlan", true);
        assertThat(diagnosisCase.ledger().all()).contains(evidence);
    }

    @Test
    void invalidStateTransitionIsRejected() {
        DiagnosisCase diagnosisCase = DiagnosisCase.open("case-1", "订单失败");

        assertThatThrownBy(() -> diagnosisCase.markDone())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("PLANNING");
    }

    @Test
    void canMoveToNeedInfoFromRunning() {
        DiagnosisCase diagnosisCase = DiagnosisCase.open("case-1", "order failed");
        diagnosisCase.adoptPlan(plan(StepStatus.PENDING));

        diagnosisCase.requireInputs(List.of("traceId"));

        assertThat(diagnosisCase.status()).isEqualTo(DiagnosisStatus.NEED_INFO);
    }

    private static DiagnosisPlan plan(StepStatus status) {
        return new DiagnosisPlan(
                "订单失败",
                List.of(Hypothesis.open("H1", "入口服务报错", 0.4)),
                List.of(new DiagnosisStep("S1", "查日志", "H1", List.of("LogQuery"), status, "")));
    }
}
