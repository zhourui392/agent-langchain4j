package com.anthropic.agentkit.domain.diagnosis;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Evidence item linking a conclusion to user input, model inference, or a tool result.
 *
 * @author zhourui(V33215020)
 * @since 2026-06-11
 */
public record Evidence(String id, EvidenceSource source, String summary, String rawExcerpt, String toolName,
                       String toolUseId, Map<String, Object> metadata, Instant observedAt,
                       Instant recordedAt) {

    public Evidence {
        id = SecretDataPolicy.required(id, "id");
        Objects.requireNonNull(source, "source");
        summary = SecretDataPolicy.required(summary, "summary");
        rawExcerpt = SecretDataPolicy.sanitize(rawExcerpt);
        toolName = SecretDataPolicy.sanitize(toolName);
        toolUseId = SecretDataPolicy.sanitize(toolUseId);
        metadata = safeMetadata(metadata);
        observedAt = Objects.requireNonNull(observedAt, "observedAt");
        recordedAt = recordedAt == null ? observedAt : recordedAt;
    }

    public Evidence(String id, EvidenceSource source, String summary, String rawExcerpt,
                    String toolName, String toolUseId, Map<String, Object> metadata,
                    Instant observedAt) {
        this(id, source, summary, rawExcerpt, toolName, toolUseId, metadata,
                observedAt, observedAt);
    }

    private static Map<String, Object> safeMetadata(Map<String, Object> values) {
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        Objects.requireNonNull(values, "metadata").forEach((key, value) -> {
            if (!SecretDataPolicy.sensitiveKey(key)) {
                result.put(key, SecretDataPolicy.sanitize(value));
            }
        });
        return Map.copyOf(result);
    }

}
