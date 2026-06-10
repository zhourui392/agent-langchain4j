package com.anthropic.cclc.application.diagnosis;

import com.anthropic.cclc.domain.diagnosis.DiagnosisPlan;
import com.anthropic.cclc.domain.permission.Decision;
import com.anthropic.cclc.domain.permission.PermissionMode;
import com.anthropic.cclc.domain.permission.PermissionPolicy;
import com.anthropic.cclc.domain.tool.Tool;
import com.anthropic.cclc.domain.tool.ToolInvocation;

import java.util.Objects;
import java.util.function.Supplier;

/**
 * Adapts diagnosis plan constraints to the kernel permission policy contract.
 *
 * @author zhourui(V33215020)
 * @since 2026-06-11
 */
public final class PlanGuardPolicy implements PermissionPolicy {

    private final Supplier<DiagnosisPlan> currentPlan;
    private final PlanGuardMode guardMode;

    public PlanGuardPolicy(Supplier<DiagnosisPlan> currentPlan, PlanGuardMode guardMode) {
        this.currentPlan = Objects.requireNonNull(currentPlan, "currentPlan");
        this.guardMode = Objects.requireNonNull(guardMode, "guardMode");
    }

    @Override
    public Decision decide(ToolInvocation invocation, Tool tool, PermissionMode mode) {
        DiagnosisPlan plan = currentPlan.get();
        boolean allowedByPlan = plan != null && plan.isToolAllowed(tool.name());
        if (allowedByPlan || guardMode == PlanGuardMode.OBSERVE) {
            return Decision.ALLOW;
        }
        return Decision.DENY;
    }
}
