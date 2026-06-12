package com.anthropic.cclc.application.diagnosis;

import com.anthropic.cclc.domain.diagnosis.DiagnosisCase;
import com.anthropic.cclc.domain.diagnosis.DiagnosisReport;

/**
 * Produces structured diagnosis reports from the current case state.
 *
 * @author zhourui(V33215020)
 * @since 2026-06-11
 */
public interface DiagnosisReporter {

    DiagnosisReport report(DiagnosisCase diagnosisCase);
}
