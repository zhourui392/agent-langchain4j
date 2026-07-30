package com.anthropic.agentkit.application.diagnosis;

import com.anthropic.agentkit.domain.diagnosis.DiagnosisPlan;
import com.anthropic.agentkit.domain.permission.Decision;
import com.anthropic.agentkit.domain.permission.PermissionMode;
import com.anthropic.agentkit.domain.permission.PermissionPolicy;
import com.anthropic.agentkit.domain.tool.Tool;
import com.anthropic.agentkit.domain.tool.ToolInvocation;

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
        boolean allowedByPlan = plan != null && plan.isToolAllowed(tool.name())
                && plan.scope().permits(invocation.args().values());
        if (!allowedByPlan) {
            log.warn("plan guard observed off-plan tool: tool={}, mode={}", tool.name(), guardMode);
        }
        return allowedByPlan || guardMode == PlanGuardMode.OBSERVE
                ? Decision.ALLOW : Decision.DENY;
    }
}
