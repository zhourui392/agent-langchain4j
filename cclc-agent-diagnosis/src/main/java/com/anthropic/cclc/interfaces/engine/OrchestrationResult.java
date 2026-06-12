package com.anthropic.cclc.interfaces.engine;

/**
 * Internal return value from the diagnosis orchestrator.
 *
 * @author zhourui(V33215020)
 * @since 2026-06-13
 */
record OrchestrationResult(String stateSnapshot, RunSummary.Usage usage) {

    OrchestrationResult {
        stateSnapshot = stateSnapshot == null ? "" : stateSnapshot;
        usage = usage == null ? RunSummary.Usage.zero() : usage;
    }
}
