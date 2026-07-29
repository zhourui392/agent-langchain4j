package com.anthropic.agentkit.domain.agent;

import java.util.Objects;
import java.util.Optional;

/** Static role, capability and limit definition; contains no state from a run. */
public record AgentSpec(
        AgentId id,
        String systemPrompt,
        ToolCapabilitySet allowedTools,
        ModelTier modelTier,
        AgentBudget budget,
        AgentRunLimits limits,
        Optional<TerminalToolSpec> terminalTool) {

    public AgentSpec {
        Objects.requireNonNull(id, "id");
        requirePrompt(systemPrompt);
        Objects.requireNonNull(allowedTools, "allowedTools");
        Objects.requireNonNull(modelTier, "modelTier");
        Objects.requireNonNull(budget, "budget");
        Objects.requireNonNull(limits, "limits");
        Objects.requireNonNull(terminalTool, "terminalTool");
        rejectTerminalCollision(allowedTools, terminalTool);
    }

    private static void requirePrompt(String prompt) {
        Objects.requireNonNull(prompt, "systemPrompt");
        if (prompt.isBlank()) {
            throw new IllegalArgumentException("systemPrompt must not be blank");
        }
    }

    private static void rejectTerminalCollision(
            ToolCapabilitySet tools, Optional<TerminalToolSpec> terminal) {
        if (terminal.isPresent() && tools.contains(terminal.orElseThrow().name())) {
            throw new IllegalArgumentException(
                    "terminal tool must not duplicate a domain tool capability");
        }
    }
}
