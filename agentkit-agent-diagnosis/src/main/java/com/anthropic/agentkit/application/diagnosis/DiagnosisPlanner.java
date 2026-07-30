package com.anthropic.agentkit.application.diagnosis;

import com.anthropic.agentkit.domain.agent.AgentRunContext;
import com.anthropic.agentkit.domain.diagnosis.DiagnosisCase;
import com.anthropic.agentkit.domain.diagnosis.DiagnosisExecutionCapabilities;
import com.anthropic.agentkit.domain.diagnosis.DiagnosisPlan;
import com.anthropic.agentkit.domain.diagnosis.Evidence;
import com.anthropic.agentkit.domain.diagnosis.OperationalContext;

/**
 * Creates and updates structured diagnosis plans.
 *
 * @author zhourui(V33215020)
 * @since 2026-06-11
 */
public interface DiagnosisPlanner {

    DiagnosisPlan createPlan(DiagnosisCase diagnosisCase, AgentRunContext context);

    default DiagnosisPlan createPlan(DiagnosisCase diagnosisCase, String conversationContext,
                                     AgentRunContext context) {
        return createPlan(diagnosisCase, context);
    }

    default DiagnosisPlan createPlan(DiagnosisCase diagnosisCase, String conversationContext,
                                     OperationalContext operationalContext,
                                     AgentRunContext context) {
        return createPlan(diagnosisCase, conversationContext, context);
    }

    default DiagnosisPlan createPlan(DiagnosisCase diagnosisCase, String conversationContext,
                                     OperationalContext operationalContext,
                                     DiagnosisExecutionCapabilities capabilities,
                                     AgentRunContext context) {
        return createPlan(diagnosisCase, conversationContext, operationalContext, context)
                .withCapabilityGeneration(capabilities.generation());
    }

    DiagnosisPlan updatePlan(DiagnosisCase diagnosisCase, Evidence evidence, AgentRunContext context);

    default DiagnosisPlan updatePlan(DiagnosisCase diagnosisCase, Evidence evidence,
                                     DiagnosisExecutionCapabilities capabilities,
                                     AgentRunContext context) {
        return updatePlan(diagnosisCase, evidence, context)
                .withCapabilityGeneration(capabilities.generation());
    }

    default DiagnosisPlan updatePlan(
            DiagnosisCase diagnosisCase, Evidence evidence, String conversationContext,
            OperationalContext operationalContext,
            DiagnosisExecutionCapabilities capabilities, AgentRunContext context) {
        return updatePlan(diagnosisCase, evidence, capabilities, context);
    }
}
