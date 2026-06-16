package com.anthropic.agentkit.domain.conversation;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TokenBudgetTest {

    @Test
    void estimatesByCharHeuristic() {
        TokenBudget budget = TokenBudget.of(1000);
        int estimate = budget.estimate("abcd".repeat(100));

        assertThat(estimate).isEqualTo(100);
    }

    @Test
    void estimatesEmptyStringAsZero() {
        TokenBudget budget = TokenBudget.of(1000);
        assertThat(budget.estimate("")).isZero();
    }

    @Test
    void flagsThresholdReachedAt85Percent() {
        TokenBudget budget = TokenBudget.of(1000);

        assertThat(budget.thresholdReached(840)).isFalse();
        assertThat(budget.thresholdReached(850)).isTrue();
        assertThat(budget.thresholdReached(900)).isTrue();
    }

    @Test
    void exposesMaxAndThresholdValues() {
        TokenBudget budget = TokenBudget.of(1000);
        assertThat(budget.maxTokens()).isEqualTo(1000);
        assertThat(budget.thresholdTokens()).isEqualTo(850);
    }

    @Test
    void rejectsNonPositiveMax() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> TokenBudget.of(0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
