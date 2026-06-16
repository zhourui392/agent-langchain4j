package com.anthropic.agentkit.domain.diagnosis;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * Evidence item linking a conclusion to user input, model inference, or a tool result.
 *
 * @author zhourui(V33215020)
 * @since 2026-06-11
 */
public record Evidence(String id, EvidenceSource source, String summary, String rawExcerpt, String toolName,
                       String toolUseId, Map<String, Object> metadata, Instant observedAt) {

    public Evidence {
        requireText(id, "id");
        Objects.requireNonNull(source, "source");
        requireText(summary, "summary");
        rawExcerpt = rawExcerpt == null ? "" : rawExcerpt;
        toolName = toolName == null ? "" : toolName;
        toolUseId = toolUseId == null ? "" : toolUseId;
        metadata = Map.copyOf(Objects.requireNonNull(metadata, "metadata"));
        observedAt = Objects.requireNonNull(observedAt, "observedAt");
    }

    private static void requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
    }
}
