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

    private static DiagnosisPlan plan() {
        return new DiagnosisPlan(
                "订单失败",
                List.of(Hypothesis.open("H1", "入口服务报错", 0.4)),
                List.of(new DiagnosisStep("S1", "查日志", "H1", List.of("LogQuery"), StepStatus.RUNNING, "")));
    }

    private static ToolInvocation invocation(String toolName) {
        return ToolInvocation.create(new ToolUseId("tu-1"), toolName, ToolArguments.empty());
    }
}
