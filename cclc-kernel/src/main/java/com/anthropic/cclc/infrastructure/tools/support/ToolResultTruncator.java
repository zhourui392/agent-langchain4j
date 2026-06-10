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
        if (content == null || content.isEmpty()) {
            return content == null ? "" : content;
        }
        String[] lines = content.split("\n", -1);
        boolean tooManyLines = lines.length > maxLines;
        boolean tooManyTokens = tokenEstimator.estimate(content) > maxTokens;
        if (!tooManyLines && !tooManyTokens) {
            return content;
        }
        return tooManyLines ? truncateByLines(lines) : truncateByChars(content);
    }

    private String truncateByLines(String[] lines) {
        int omittedLines = lines.length - headLines - tailLines;
        if (omittedLines <= 0) {
            return String.join("\n", lines);
        }
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < headLines; i++) {
            out.append(lines[i]).append('\n');
        }
        out.append("... +").append(omittedLines).append(" lines (~")
                .append(estimateOmittedLines(lines)).append(" tokens) omitted ...");
        for (int i = lines.length - tailLines; i < lines.length; i++) {
            out.append('\n').append(lines[i]);
        }
        return out.toString();
    }

    private int estimateOmittedLines(String[] lines) {
        StringBuilder omitted = new StringBuilder();
        for (int i = headLines; i < lines.length - tailLines; i++) {
            omitted.append(lines[i]).append('\n');
        }
        return tokenEstimator.estimate(omitted.toString());
    }

    private String truncateByChars(String content) {
        int budgetChars = maxTokens * 4;
        int headChars = Math.min(content.length(), budgetChars * 2 / 3);
        int tailChars = Math.min(content.length() - headChars, budgetChars - headChars);
        if (headChars + tailChars >= content.length()) {
            return content;
        }
        String head = content.substring(0, headChars);
        String tail = content.substring(content.length() - tailChars);
        int omittedTokens = tokenEstimator.estimate(content.substring(headChars, content.length() - tailChars));
        return head + "\n... ~" + omittedTokens + " tokens omitted ...\n" + tail;
    }
}
