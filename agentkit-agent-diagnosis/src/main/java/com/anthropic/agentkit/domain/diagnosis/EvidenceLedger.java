package com.anthropic.agentkit.domain.diagnosis;

import com.anthropic.agentkit.domain.tool.ToolResult;
import com.anthropic.agentkit.domain.tool.ToolUseRequest;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Aggregate-owned evidence collection.
 *
 * @author zhourui(V33215020)
 * @since 2026-06-11
 */
public final class EvidenceLedger {

    private static final int MAX_SUMMARY_CHARACTERS = 512;
    private static final int MAX_RAW_EXCERPT_CHARACTERS = 4096;
    private static final String EXCERPT_TRUNCATION_MARKER = "\n...<truncated>...\n";

    private final List<Evidence> evidence = new ArrayList<>();
    private final Clock clock;

    public EvidenceLedger() {
        this(Clock.systemUTC());
    }

    EvidenceLedger(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public Evidence addModelInference(String summary) {
        Instant now = clock.instant();
        return add(new Evidence(nextId(), EvidenceSource.MODEL_INFERENCE, summary, summary,
                "", "", Map.of(), now, now));
    }

    public Evidence addToolResult(ToolUseRequest request, ToolResult result, boolean offPlan) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        result.metadata().forEach(metadata::put);
        metadata.put("offPlan", offPlan);
        metadata.put("success", result.success());
        Instant recordedAt = clock.instant();
        return add(new Evidence(nextId(), EvidenceSource.TOOL_RESULT,
                summary(result.content()), excerpt(result.content()),
                request.toolName(), request.id().value(), metadata,
                observedAt(metadata, recordedAt), recordedAt));
    }

    public Evidence addExisting(Evidence existing) {
        return add(existing);
    }

    public List<Evidence> all() {
        return List.copyOf(evidence);
    }

    private Evidence add(Evidence item) {
        evidence.add(Objects.requireNonNull(item, "item"));
        return item;
    }

    private String nextId() {
        return "E" + (evidence.size() + 1);
    }

    private String summary(String content) {
        String value = Objects.requireNonNull(content, "content").lines()
                .map(String::trim).filter(line -> !line.isEmpty()).findFirst()
                .orElse("(empty tool result)");
        return value.length() <= MAX_SUMMARY_CHARACTERS
                ? value : value.substring(0, MAX_SUMMARY_CHARACTERS);
    }

    private String excerpt(String content) {
        String value = Objects.requireNonNull(content, "content");
        if (value.length() <= MAX_RAW_EXCERPT_CHARACTERS) {
            return value;
        }
        int retained = MAX_RAW_EXCERPT_CHARACTERS - EXCERPT_TRUNCATION_MARKER.length();
        int head = retained / 2;
        int tail = retained - head;
        return value.substring(0, head) + EXCERPT_TRUNCATION_MARKER
                + value.substring(value.length() - tail);
    }

    private Instant observedAt(Map<String, Object> metadata, Instant fallback) {
        Object value = metadata.get(DiagnosisToolMetadata.QUERY_END);
        if (value == null) {
            return fallback;
        }
        try {
            return Instant.parse(value.toString());
        } catch (RuntimeException invalidTimestamp) {
            return fallback;
        }
    }
}
