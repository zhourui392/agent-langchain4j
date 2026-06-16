package com.anthropic.agentkit.domain.diagnosis;

import com.anthropic.agentkit.domain.tool.ToolResult;
import com.anthropic.agentkit.domain.tool.ToolUseRequest;

import java.time.Clock;
import java.util.ArrayList;
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

    private final List<Evidence> evidence = new ArrayList<>();
    private final Clock clock;

    public EvidenceLedger() {
        this(Clock.systemUTC());
    }

    EvidenceLedger(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public Evidence addModelInference(String summary) {
        return add(new Evidence(nextId(), EvidenceSource.MODEL_INFERENCE, summary, summary,
                "", "", Map.of(), clock.instant()));
    }

    public Evidence addToolResult(ToolUseRequest request, ToolResult result, boolean offPlan) {
        Map<String, Object> metadata = Map.of("offPlan", offPlan, "success", result.success());
        return add(new Evidence(nextId(), EvidenceSource.TOOL_RESULT, result.content(), result.content(),
                request.toolName(), request.id().value(), metadata, clock.instant()));
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
}
