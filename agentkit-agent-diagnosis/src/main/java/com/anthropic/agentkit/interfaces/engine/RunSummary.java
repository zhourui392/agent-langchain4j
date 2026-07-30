package com.anthropic.agentkit.interfaces.engine;

import com.anthropic.agentkit.domain.diagnosis.SecretDataPolicy;

import java.util.List;
import java.util.Objects;

/**
 * Terminal state returned to the host after a diagnosis run completes.
 *
 * @author zhourui(V33215020)
 * @since 2026-06-13
 */
public record RunSummary(ExitReason reason, String stateSnapshot, Usage usage, String errorDetail,
                         DiagnosisOutcome outcome, List<DiagnosisBlockerView> blockers) {

    private static final int EXIT_SUCCESS = 0;
    private static final int EXIT_CANCELLED = -1;
    private static final int EXIT_ERROR = 1;

    public RunSummary {
        reason = Objects.requireNonNull(reason, "reason");
        stateSnapshot = SecretDataPolicy.sanitize(stateSnapshot);
        usage = usage == null ? Usage.zero() : usage;
        errorDetail = SecretDataPolicy.sanitize(errorDetail);
        outcome = outcome == null ? defaultOutcome(reason) : outcome;
        blockers = List.copyOf(blockers == null ? List.of() : blockers);
    }

    public RunSummary(ExitReason reason, String stateSnapshot, Usage usage, String errorDetail) {
        this(reason, stateSnapshot, usage, errorDetail, defaultOutcome(reason), List.of());
    }

    /**
     * Maps the structured terminal reason back to the legacy CLI-style code.
     *
     * @return 0 for success, -1 for stop/timeout, 1 for error/rejected
     */
    public int legacyExitCode() {
        return switch (reason) {
            case SUCCESS -> EXIT_SUCCESS;
            case STOPPED, TIMEOUT -> EXIT_CANCELLED;
            case ERROR, REJECTED -> EXIT_ERROR;
        };
    }

    private static DiagnosisOutcome defaultOutcome(ExitReason reason) {
        return switch (reason) {
            case SUCCESS -> DiagnosisOutcome.COMPLETED;
            case STOPPED, TIMEOUT -> DiagnosisOutcome.CANCELLED;
            case ERROR, REJECTED -> DiagnosisOutcome.FAILED;
        };
    }

    /**
     * Aggregated token usage for all LLM calls in the run.
     *
     * @author zhourui(V33215020)
     * @since 2026-06-13
     */
    public record Usage(long inputTokens, long outputTokens, long cacheReadInputTokens) {
        public static Usage zero() {
            return new Usage(0, 0, 0);
        }

        public Usage plus(int input, int output, int cacheReadInput) {
            return new Usage(
                    inputTokens + input,
                    outputTokens + output,
                    cacheReadInputTokens + cacheReadInput);
        }
    }
}
