package com.anthropic.agentkit.infrastructure.coding;

import com.anthropic.agentkit.domain.agent.CapabilityDescriptor;
import com.anthropic.agentkit.domain.agent.ToolCapabilitySet;
import com.anthropic.agentkit.domain.tool.Tool;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Builds manifest capabilities from the same role constants used at runtime. */
public final class CodingCapabilities {

    private CodingCapabilities() {
    }

    public static CapabilityDescriptor describe(List<Tool> codingTools) {
        LinkedHashSet<String> allowed = new LinkedHashSet<>();
        codingTools.forEach(tool -> allowed.add(tool.name()));
        return new CapabilityDescriptor(
                ToolCapabilitySet.copyOf(allowed), terminalTools());
    }

    private static Set<String> terminalTools() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(List.of(
                StructuredCodingPlanner.TOOL_NAME,
                StructuredCodingPatcher.TOOL_NAME,
                StructuredCodingReviewer.TOOL_NAME)));
    }
}
