package com.anthropic.cclc.domain.tool;

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
    void transitionsAllowedToCompleted() {
        ToolInvocation inv = pendingBash();
        inv.allow();
        assertThat(inv.state()).isEqualTo(InvocationState.ALLOWED);

        inv.complete(ToolResult.ok("done"));
        assertThat(inv.state()).isEqualTo(InvocationState.COMPLETED);
        assertThat(inv.result()).isEqualTo(ToolResult.ok("done"));
    }

    @Test
    void cannotCompleteWithoutPermissionDecision() {
        ToolInvocation inv = pendingBash();
        assertThatThrownBy(() -> inv.complete(ToolResult.ok("done")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("PENDING");
    }

    @Test
    void deniedIsTerminalAndProducesError() {
        ToolInvocation inv = pendingBash();
        inv.deny();
        assertThat(inv.state()).isEqualTo(InvocationState.DENIED);

        assertThatThrownBy(inv::allow)
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void failTransitionsFromAllowed() {
        ToolInvocation inv = pendingBash();
        inv.allow();
        inv.fail(ToolResult.error("boom"));
        assertThat(inv.state()).isEqualTo(InvocationState.FAILED);
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
    void cannotFailFromPending() {
        ToolInvocation inv = pendingBash();
        assertThatThrownBy(() -> inv.fail(ToolResult.error("boom")))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void completedIsTerminal() {
        ToolInvocation inv = pendingBash();
        inv.allow();
        inv.complete(ToolResult.ok("done"));
        assertThatThrownBy(() -> inv.fail(ToolResult.error("late")))
                .isInstanceOf(IllegalStateException.class);
    }

    private static ToolInvocation pendingBash() {
        return ToolInvocation.create(new ToolUseId("u1"), "Bash", ToolArguments.empty());
    }
}
