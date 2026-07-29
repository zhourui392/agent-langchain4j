package com.anthropic.agentkit.application.diagnosis;

import com.anthropic.agentkit.domain.agent.AgentRunContext;
import com.anthropic.agentkit.domain.diagnosis.DiagnosisCase;
import com.anthropic.agentkit.domain.diagnosis.DiagnosisReport;

/**
 * Produces structured diagnosis reports from the current case state.
 *
 * @author zhourui(V33215020)
 * @since 2026-06-11
 */
public interface DiagnosisReporter {

    DiagnosisReport report(DiagnosisCase diagnosisCase, AgentRunContext context);
}
