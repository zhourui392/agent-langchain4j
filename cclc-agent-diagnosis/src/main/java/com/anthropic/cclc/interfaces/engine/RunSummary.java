package com.anthropic.cclc.interfaces.engine;

import java.util.Objects;

/**
 * Terminal state returned to the host after a diagnosis run completes.
 *
 * @author zhourui(V33215020)
 * @since 2026-06-13
 */
public record RunSummary(ExitReason reason, String stateSnapshot, Usage usage, String errorDetail) {

    private static final int EXIT_SUCCESS = 0;
    private static final int EXIT_CANCELLED = -1;
    private static final int EXIT_ERROR = 1;

    public RunSummary {
        reason = Objects.requireNonNull(reason, "reason");
        stateSnapshot = stateSnapshot == null ? "" : stateSnapshot;
        usage = usage == null ? Usage.zero() : usage;
        errorDetail = errorDetail == null ? "" : errorDetail;
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
