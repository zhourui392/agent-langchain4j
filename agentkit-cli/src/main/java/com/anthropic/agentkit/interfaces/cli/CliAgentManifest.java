package com.anthropic.agentkit.interfaces.cli;

import com.anthropic.agentkit.domain.agent.AgentId;
import com.anthropic.agentkit.domain.agent.AgentManifest;
import com.anthropic.agentkit.domain.agent.CapabilityDescriptor;
import com.anthropic.agentkit.domain.agent.ToolCapabilitySet;
import com.anthropic.agentkit.domain.tool.ToolRegistry;

import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

public final class CliAgentManifest {

    public static final AgentId ID = AgentId.of("assistant");

    private CliAgentManifest() {
    }

    public static AgentManifest<CliAgentRequest, CliAgentResult> create(
            CliAgentEntryPoint entryPoint, ToolRegistry tools) {
        Objects.requireNonNull(tools, "tools");
        CapabilityDescriptor capabilities = new CapabilityDescriptor(
                ToolCapabilitySet.copyOf(new LinkedHashSet<>(tools.names())), Set.of());
        return new AgentManifest<>(ID, "Interactive CLI coding assistant",
                Objects.requireNonNull(entryPoint, "entryPoint"), Set.of(), capabilities);
    }
}
