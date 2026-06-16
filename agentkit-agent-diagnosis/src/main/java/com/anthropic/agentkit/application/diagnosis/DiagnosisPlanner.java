package com.anthropic.agentkit.application.diagnosis;

import com.anthropic.agentkit.domain.diagnosis.DiagnosisCase;
import com.anthropic.agentkit.domain.diagnosis.DiagnosisPlan;
import com.anthropic.agentkit.domain.diagnosis.Evidence;

/**
 * Creates and updates structured diagnosis plans.
 *
 * @author zhourui(V33215020)
 * @since 2026-06-11
 */
public interface DiagnosisPlanner {

    DiagnosisPlan createPlan(DiagnosisCase diagnosisCase);

    DiagnosisPlan updatePlan(DiagnosisCase diagnosisCase, Evidence evidence);
}
