package com.anthropic.agentkit.interfaces.engine;

import com.anthropic.agentkit.application.coding.CodingPipeline;
import com.anthropic.agentkit.domain.agent.AgentId;
import com.anthropic.agentkit.domain.agent.AgentManifest;
import com.anthropic.agentkit.domain.agent.CapabilityDescriptor;
import com.anthropic.agentkit.domain.agent.ConfigKey;
import com.anthropic.agentkit.domain.agent.ToolCapabilitySet;
import com.anthropic.agentkit.domain.coding.CodingTask;
import com.anthropic.agentkit.domain.port.LlmClient;
import com.anthropic.agentkit.domain.tool.Tool;
import com.anthropic.agentkit.infrastructure.coding.StructuredCodingPatcher;
import com.anthropic.agentkit.infrastructure.coding.StructuredCodingPlanner;
import com.anthropic.agentkit.infrastructure.coding.StructuredCodingReviewer;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Explicit assembly root for the coding agent package. */
public final class CodingEngineBuilder {

    private static final AgentId AGENT_ID = AgentId.of("coding");
    private static final String DESCRIPTION =
            "Plan, implement, and review a scoped coding requirement";
    private static final Set<String> TERMINAL_TOOLS = orderedSet(
            "update_plan", "submit_patch", "submit_review");

    private LlmClient llm;
    private List<Tool> codingTools = List.of();
    private Set<ConfigKey> requiredConfigKeys = Set.of();

    private CodingEngineBuilder() {
    }

    public static CodingEngineBuilder create() {
        return new CodingEngineBuilder();
    }

    public CodingEngineBuilder llm(LlmClient llm) {
        this.llm = Objects.requireNonNull(llm, "llm");
        return this;
    }

    public CodingEngineBuilder codingTools(List<Tool> codingTools) {
        this.codingTools = List.copyOf(Objects.requireNonNull(codingTools, "codingTools"));
        return this;
    }

    public CodingEngineBuilder requiredConfigKeys(Set<ConfigKey> keys) {
        this.requiredConfigKeys = Set.copyOf(Objects.requireNonNull(keys, "keys"));
        return this;
    }

    public CodingEngine build() {
        requireLlm();
        return new DefaultCodingEngine(new CodingPipeline(
                new StructuredCodingPlanner(llm),
                new StructuredCodingPatcher(llm, codingTools),
                new StructuredCodingReviewer(llm)));
    }

    public AgentManifest<CodingRequest, CodingTask> buildManifest() {
        return new AgentManifest<>(AGENT_ID, DESCRIPTION, build(), requiredConfigKeys,
                new CapabilityDescriptor(toolCapabilities(), TERMINAL_TOOLS));
    }

    private ToolCapabilitySet toolCapabilities() {
        LinkedHashSet<String> names = new LinkedHashSet<>();
        codingTools.forEach(tool -> names.add(tool.name()));
        return ToolCapabilitySet.copyOf(names);
    }

    private void requireLlm() {
        if (llm == null) {
            throw new IllegalStateException("llm must be configured");
        }
    }

    private static Set<String> orderedSet(String... names) {
        return java.util.Collections.unmodifiableSet(
                new LinkedHashSet<>(List.of(names)));
    }
}
