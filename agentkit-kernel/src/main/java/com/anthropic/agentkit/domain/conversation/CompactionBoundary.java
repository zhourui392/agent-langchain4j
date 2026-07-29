package com.anthropic.agentkit.domain.conversation;

import com.anthropic.agentkit.domain.message.SystemMessage;

import java.util.Objects;

/** Explicit projection boundary created when older conversation history is summarized. */
public record CompactionBoundary(
        int sourceStartInclusive,
        int sourceEndExclusive,
        int originalEstimatedTokens,
        int summaryVersion,
        String summary) {

    public CompactionBoundary {
        if (sourceStartInclusive < 0 || sourceEndExclusive <= sourceStartInclusive) {
            throw new IllegalArgumentException("compaction source range must not be empty");
        }
        if (originalEstimatedTokens < 0) {
            throw new IllegalArgumentException("originalEstimatedTokens must not be negative");
        }
        if (summaryVersion <= 0) {
            throw new IllegalArgumentException("summaryVersion must be positive");
        }
        summary = Objects.requireNonNull(summary, "summary").trim();
        if (summary.isEmpty()) {
            throw new IllegalArgumentException("summary must not be blank");
        }
    }

    public SystemMessage asMessage() {
        return SystemMessage.of("[Compacted conversation summary v"
                + summaryVersion + "]\n" + summary);
    }
}
