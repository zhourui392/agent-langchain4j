package com.anthropic.agentkit.domain.agent;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentBudgetTest {

    @Test
    void rejectsNegativeLimits() {
        assertThatThrownBy(() -> new AgentBudget(-1, 0, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxTurns");
        assertThatThrownBy(() -> new AgentBudget(1, -1, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxToolCalls");
        assertThatThrownBy(() -> new AgentBudget(1, 0, -1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxInputTokens");
    }

    @Test
    void unlimitedBudgetDoesNotRejectLargeCounts() {
        AgentBudget budget = AgentBudget.unlimited();

        assertThat(budget.exceedsTurns(Integer.MAX_VALUE)).isFalse();
        assertThat(budget.exceedsToolCalls(Integer.MAX_VALUE)).isFalse();
        assertThat(budget.exceedsInputTokens(Long.MAX_VALUE)).isFalse();
    }
}
