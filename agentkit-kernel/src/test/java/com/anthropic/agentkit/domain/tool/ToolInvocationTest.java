package com.anthropic.agentkit.domain.tool;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ToolInvocationTest {

    @Test
    void startsAsPending() {
        ToolInvocation inv = pendingBash();
        assertThat(inv.state()).isEqualTo(InvocationState.PENDING);
        assertThat(inv.result()).isNull();
    }

    @Test
    void transitionsAllowedToSettled() {
        ToolInvocation inv = pendingBash();
        inv.allow();
        assertThat(inv.state()).isEqualTo(InvocationState.ALLOWED);

        inv.settle(ToolResult.ok("done"));
        assertThat(inv.state()).isEqualTo(InvocationState.SETTLED);
        assertThat(inv.result()).isEqualTo(ToolResult.ok("done"));
    }

    @Test
    void cannotCompleteWithoutPermissionDecision() {
        ToolInvocation inv = pendingBash();
        assertThatThrownBy(() -> inv.settle(ToolResult.ok("done")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("PENDING");
    }

    @Test
    void deniedSettlesWithoutAllowingExecution() {
        ToolInvocation inv = pendingBash();
        ToolResult denied = ToolResult.of(ToolResultStatus.DENIED, "denied");

        inv.settle(denied);

        assertThat(inv.state()).isEqualTo(InvocationState.SETTLED);
        assertThat(inv.result()).isEqualTo(denied);
        assertThatThrownBy(inv::allow)
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void errorTransitionsFromAllowedToSettled() {
        ToolInvocation inv = pendingBash();
        inv.allow();
        inv.settle(ToolResult.error("boom"));
        assertThat(inv.state()).isEqualTo(InvocationState.SETTLED);
        assertThat(inv.result()).isEqualTo(ToolResult.error("boom"));
    }

    @Test
    void cannotAllowTwice() {
        ToolInvocation inv = pendingBash();
        inv.allow();
        assertThatThrownBy(inv::allow)
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void executionErrorCannotSettleFromPending() {
        ToolInvocation inv = pendingBash();
        assertThatThrownBy(() -> inv.settle(ToolResult.error("boom")))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void settledInvocationRejectsAnotherOutcome() {
        ToolInvocation inv = pendingBash();
        inv.allow();
        inv.settle(ToolResult.ok("done"));
        assertThatThrownBy(() -> inv.settle(ToolResult.error("late")))
                .isInstanceOf(IllegalStateException.class);
    }

    private static ToolInvocation pendingBash() {
        return ToolInvocation.create(new ToolUseId("u1"), "Bash", ToolArguments.empty());
    }
}
