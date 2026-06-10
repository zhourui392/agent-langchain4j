package com.anthropic.cclc.application.diagnosis;

import com.anthropic.cclc.domain.diagnosis.DiagnosisCase;
import com.anthropic.cclc.domain.diagnosis.DiagnosisPlan;
import com.anthropic.cclc.domain.diagnosis.Evidence;

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
