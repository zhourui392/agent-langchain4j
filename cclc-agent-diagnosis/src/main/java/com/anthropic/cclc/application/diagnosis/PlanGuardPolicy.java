package com.anthropic.cclc.application.diagnosis;

import com.anthropic.cclc.domain.diagnosis.DiagnosisPlan;
import com.anthropic.cclc.domain.permission.Decision;
import com.anthropic.cclc.domain.permission.PermissionMode;
import com.anthropic.cclc.domain.permission.PermissionPolicy;
import com.anthropic.cclc.domain.tool.Tool;
import com.anthropic.cclc.domain.tool.ToolInvocation;

import java.util.Objects;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Adapts diagnosis plan constraints to the kernel permission policy contract.
 *
 * @author zhourui(V33215020)
 * @since 2026-06-11
 */
public final class PlanGuardPolicy implements PermissionPolicy {

    private static final Logger log = LoggerFactory.getLogger(PlanGuardPolicy.class);

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
            if (!allowedByPlan && guardMode == PlanGuardMode.OBSERVE) {
                log.warn("plan guard observed off-plan tool: tool={}, mode={}", tool.name(), guardMode);
            }
            return Decision.ALLOW;
        }
        log.warn("plan guard blocked tool: tool={}, mode={}", tool.name(), guardMode);
        return Decision.DENY;
    }
}
