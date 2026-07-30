package com.anthropic.agentkit.application.diagnosis;

import com.anthropic.agentkit.domain.diagnosis.DiagnosisPlan;
import com.anthropic.agentkit.domain.diagnosis.DiagnosisStep;
import com.anthropic.agentkit.domain.diagnosis.Hypothesis;
import com.anthropic.agentkit.domain.diagnosis.StepStatus;
import com.anthropic.agentkit.domain.permission.Decision;
import com.anthropic.agentkit.domain.permission.PermissionMode;
import com.anthropic.agentkit.domain.tool.ToolArguments;
import com.anthropic.agentkit.domain.tool.ToolInvocation;
import com.anthropic.agentkit.domain.tool.ToolUseId;
import com.anthropic.agentkit.testsupport.FakeTool;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.time.Instant;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class PlanGuardPolicyTest {

    @Test
    void observeModeAllowsOffPlanTool() {
        PlanGuardPolicy policy = new PlanGuardPolicy(() -> plan(), PlanGuardMode.OBSERVE);

        Decision decision = policy.decide(invocation("RedisRead"),
                FakeTool.readOnlyReturning("RedisRead", "ok"), PermissionMode.BYPASS);

        assertThat(decision).isEqualTo(Decision.ALLOW);
    }

    @Test
    void observeModeAllowsToolDeclaredByCurrentStep() {
        PlanGuardPolicy policy = new PlanGuardPolicy(() -> plan(), PlanGuardMode.OBSERVE);

        Decision decision = policy.decide(invocation("LogQuery"),
                FakeTool.readOnlyReturning("LogQuery", "ok"), PermissionMode.BYPASS);

        assertThat(decision).isEqualTo(Decision.ALLOW);
    }

    @Test
    void enforceModeDeniesToolArgumentsThatExpandPlanScope() {
        PlanGuardPolicy policy = new PlanGuardPolicy(() -> scopedPlan(), PlanGuardMode.ENFORCE);

        Decision decision = policy.decide(invocation("LogQuery", Map.of(
                        "service", "payment",
                        "startTime", "2026-07-29T23:00:00Z",
                        "endTime", "2026-07-30T02:00:00Z")),
                FakeTool.readOnlyReturning("LogQuery", "ok"), PermissionMode.BYPASS);

        assertThat(decision).isEqualTo(Decision.DENY);
    }

    @Test
    void enforceModeAllowsToolArgumentsInsidePlanScope() {
        PlanGuardPolicy policy = new PlanGuardPolicy(() -> scopedPlan(), PlanGuardMode.ENFORCE);

        Decision decision = policy.decide(invocation("LogQuery", Map.of(
                        "service", "agent-web",
                        "startTime", "2026-07-30T00:30:00Z",
                        "endTime", "2026-07-30T02:00:00Z")),
                FakeTool.readOnlyReturning("LogQuery", "ok"), PermissionMode.BYPASS);

        assertThat(decision).isEqualTo(Decision.ALLOW);
    }

    private static DiagnosisPlan plan() {
        return new DiagnosisPlan(
                "订单失败",
                List.of(Hypothesis.open("H1", "入口服务报错", 0.4)),
                List.of(new DiagnosisStep("S1", "查日志", "H1", List.of("LogQuery"), StepStatus.RUNNING, "")));
    }

    private static DiagnosisPlan scopedPlan() {
        return new DiagnosisPlan(
                "订单失败", List.of(Hypothesis.open("H1", "入口服务报错", 0.4)),
                List.of(new DiagnosisStep(
                        "S1", "查日志", "H1", List.of("LogQuery"), StepStatus.RUNNING, "")),
                List.of(), new com.anthropic.agentkit.domain.diagnosis.DiagnosisScope(
                        com.anthropic.agentkit.domain.diagnosis.EnvironmentRef.named("test"),
                        Set.of("agent-web"), new com.anthropic.agentkit.domain.diagnosis.TimeWindow(
                        Instant.parse("2026-07-30T00:00:00Z"),
                        Instant.parse("2026-07-30T02:00:00Z")), Map.of(), Map.of()));
    }

    private static ToolInvocation invocation(String toolName) {
        return ToolInvocation.create(new ToolUseId("tu-1"), toolName, ToolArguments.empty());
    }

    private static ToolInvocation invocation(String toolName, Map<String, Object> arguments) {
        return ToolInvocation.create(
                new ToolUseId("tu-scoped"), toolName, ToolArguments.of(arguments));
    }
}
