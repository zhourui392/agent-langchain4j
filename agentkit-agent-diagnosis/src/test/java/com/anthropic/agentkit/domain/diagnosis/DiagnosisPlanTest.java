package com.anthropic.agentkit.domain.diagnosis;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DiagnosisPlanTest {

    @Test
    void requiresProblemStatementAndStepHypothesisLink() {
        Hypothesis hypothesis = Hypothesis.open("H1", "入口服务报错", 0.4);

        assertThatThrownBy(() -> new DiagnosisPlan(
                "", List.of(hypothesis), List.of(step("S1", "missing", StepStatus.PENDING))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("problemStatement");

        assertThatThrownBy(() -> new DiagnosisPlan(
                "订单失败", List.of(hypothesis), List.of(step("S1", "H404", StepStatus.PENDING))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("hypothesisId");
    }

    @Test
    void allowsToolOnlyForPendingOrRunningSteps() {
        DiagnosisPlan plan = new DiagnosisPlan(
                "订单失败",
                List.of(Hypothesis.open("H1", "入口服务报错", 0.4)),
                List.of(
                        step("S1", "H1", StepStatus.PENDING),
                        new DiagnosisStep("S2", "已完成", "H1", List.of("MysqlRead"), StepStatus.DONE, "")));

        assertThat(plan.isToolAllowed("LogQuery")).isTrue();
        assertThat(plan.isToolAllowed("MysqlRead")).isFalse();
        assertThat(plan.isToolAllowed("RedisRead")).isFalse();
    }

    @Test
    void tracksMissingInputs() {
        DiagnosisPlan plan = new DiagnosisPlan(
                "order failed",
                List.of(Hypothesis.open("H1", "missing trace", 0.4)),
                List.of(step("S1", "H1", StepStatus.PENDING)),
                List.of("timeWindow", "traceId"));

        assertThat(plan.needsMoreInformation()).isTrue();
        assertThat(plan.missingInputs()).containsExactly("timeWindow", "traceId");
    }

    @Test
    void carriesTypedDiagnosisScopeWhileKeepingLegacyConstructors() {
        DiagnosisScope scope = new DiagnosisScope(
                EnvironmentRef.named("test"), Set.of("agent-web"),
                new TimeWindow(Instant.parse("2026-07-30T00:00:00Z"),
                        Instant.parse("2026-07-30T02:00:00Z")), Map.of(), Map.of());
        DiagnosisPlan scoped = new DiagnosisPlan(
                "errors", List.of(Hypothesis.open("H1", "application error", 0.4)),
                List.of(step("S1", "H1", StepStatus.PENDING)), List.of(), scope);

        assertThat(scoped.scope()).isEqualTo(scope);
        assertThat(new DiagnosisPlan("hello", List.of(), List.of()).scope().isKnown()).isFalse();
    }

    private static DiagnosisStep step(String id, String hypothesisId, StepStatus status) {
        return new DiagnosisStep(id, "查日志", hypothesisId, List.of("LogQuery"), status, "");
    }
}
