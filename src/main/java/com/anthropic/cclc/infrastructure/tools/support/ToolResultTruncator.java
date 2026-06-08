package com.anthropic.cclc.infrastructure.tools.support;

import com.anthropic.cclc.domain.conversation.TokenEstimator;

import java.util.Objects;

/**
 * Trims oversized tool output before it enters the conversation: keeps the head
 * and tail, replacing the middle with an omission marker. Diagnosis output
 * (logs, ES hits) can be huge and blow the context/cost budget. Truncation
 * triggers on either too many lines or too many estimated tokens. Stub for Red.
 *
 * @author zhourui(V33215020)
 * @since 2026-06-08
 */
public final class ToolResultTruncator {

    private final int maxLines;
    private final int headLines;
    private final int tailLines;
    private final int maxTokens;
    private final TokenEstimator tokenEstimator;

    public ToolResultTruncator(int maxLines, int headLines, int tailLines,
                               int maxTokens, TokenEstimator tokenEstimator) {
        this.maxLines = maxLines;
        this.headLines = headLines;
        this.tailLines = tailLines;
        this.maxTokens = maxTokens;
        this.tokenEstimator = Objects.requireNonNull(tokenEstimator, "tokenEstimator");
    }

    public static ToolResultTruncator withDefaults() {
        return new ToolResultTruncator(200, 80, 40, 4000, TokenEstimator.CHAR_HEURISTIC);
    }

    public String truncate(String content) {
        return content;
    }
}
