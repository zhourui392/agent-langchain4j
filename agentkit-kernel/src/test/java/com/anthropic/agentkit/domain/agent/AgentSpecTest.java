package com.anthropic.agentkit.domain.agent;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentSpecTest {

    @Test
    void materializesStaticRoleWithoutRunState() {
        TerminalToolSpec terminal = new TerminalToolSpec(
                "submit_finding", "Submit a finding", objectSchema());

        AgentSpec spec = new AgentSpec(
                AgentId.of("researcher"),
                "Investigate one bounded question.",
                ToolCapabilitySet.of("Read", "Grep"),
                ModelTier.CAPABLE,
                AgentBudget.of(6, 8, 20_000),
                AgentRunLimits.defaults(),
                Optional.of(terminal));

        assertThat(spec.id()).isEqualTo(AgentId.of("researcher"));
        assertThat(spec.allowedTools().names()).containsExactlyInAnyOrder("Read", "Grep");
        assertThat(spec.terminalTool()).contains(terminal);
    }

    @Test
    void capabilitySetRejectsBlankNamesAndAnswersSubsetChecks() {
        ToolCapabilitySet child = ToolCapabilitySet.of("Read", "Grep");
        ToolCapabilitySet parent = ToolCapabilitySet.of("Read", "Grep", "Glob");

        assertThat(child.isSubsetOf(parent)).isTrue();
        assertThat(parent.isSubsetOf(child)).isFalse();
        assertThatThrownBy(() -> ToolCapabilitySet.of("Read", " "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("tool capability");
    }

    @Test
    void rejectsTerminalToolThatDuplicatesDomainCapability() {
        TerminalToolSpec terminal = new TerminalToolSpec(
                "submit", "Submit output", objectSchema());

        assertThatThrownBy(() -> new AgentSpec(
                AgentId.of("duplicate-terminal"),
                "Finish with submit.",
                ToolCapabilitySet.of("submit"),
                ModelTier.DEFAULT,
                AgentBudget.unlimited(),
                AgentRunLimits.defaults(),
                Optional.of(terminal)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("terminal");
    }

    private static String objectSchema() {
        return "{\"type\":\"object\",\"properties\":{\"summary\":{\"type\":\"string\"}}}";
    }
}
