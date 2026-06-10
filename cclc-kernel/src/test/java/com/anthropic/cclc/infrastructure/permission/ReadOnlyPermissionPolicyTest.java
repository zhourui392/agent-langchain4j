package com.anthropic.cclc.infrastructure.permission;

import com.anthropic.cclc.domain.permission.Decision;
import com.anthropic.cclc.domain.permission.PermissionMode;
import com.anthropic.cclc.domain.tool.Tool;
import com.anthropic.cclc.domain.tool.ToolArguments;
import com.anthropic.cclc.domain.tool.ToolInvocation;
import com.anthropic.cclc.domain.tool.ToolUseId;
import com.anthropic.cclc.testsupport.FakeTool;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ReadOnlyPermissionPolicyTest {

    private final ReadOnlyPermissionPolicy policy = new ReadOnlyPermissionPolicy();

    @Test
    void allowsReadOnlyTool() {
        Tool readOnlyTool = FakeTool.readOnlyReturning("LogQuery", "ok");

        assertThat(decideUnder(readOnlyTool, PermissionMode.BYPASS)).isEqualTo(Decision.ALLOW);
    }

    @Test
    void deniesWriteTool() {
        Tool writeTool = FakeTool.returning("FileWrite", "ok");

        assertThat(decideUnder(writeTool, PermissionMode.BYPASS)).isEqualTo(Decision.DENY);
    }

    @Test
    void deniesBashByDefault() {
        Tool bash = FakeTool.returning("Bash", "ok");

        assertThat(decideUnder(bash, PermissionMode.BYPASS)).isEqualTo(Decision.DENY);
    }

    @Test
    void neverAsksRegardlessOfMode() {
        Tool writeTool = FakeTool.returning("FileWrite", "ok");

        for (PermissionMode mode : PermissionMode.values()) {
            assertThat(decideUnder(writeTool, mode))
                    .as("mode %s must not yield ASK", mode)
                    .isNotEqualTo(Decision.ASK);
        }
    }

    private Decision decideUnder(Tool tool, PermissionMode mode) {
        ToolInvocation invocation = ToolInvocation.create(
                new ToolUseId("tu-1"), tool.name(), ToolArguments.empty());
        return policy.decide(invocation, tool, mode);
    }
}
