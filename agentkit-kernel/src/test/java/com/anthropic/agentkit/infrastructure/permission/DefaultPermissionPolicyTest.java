package com.anthropic.agentkit.infrastructure.permission;

import com.anthropic.agentkit.domain.permission.Decision;
import com.anthropic.agentkit.domain.permission.PermissionMode;
import com.anthropic.agentkit.domain.tool.ExecutionContext;
import com.anthropic.agentkit.domain.tool.Tool;
import com.anthropic.agentkit.domain.tool.ToolArguments;
import com.anthropic.agentkit.domain.tool.ToolInvocation;
import com.anthropic.agentkit.domain.tool.ToolResult;
import com.anthropic.agentkit.domain.tool.ToolUseId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class DefaultPermissionPolicyTest {

    private final DefaultPermissionPolicy policy = new DefaultPermissionPolicy();

    @ParameterizedTest(name = "[{index}] mode={0} readOnly={1} name={2} → {3}")
    @CsvSource({
            "DEFAULT, true,  Read, ALLOW",
            "DEFAULT, false, Bash, ASK",
            "DEFAULT, false, Write, ASK",
            "PLAN,    true,  Read, ALLOW",
            "PLAN,    false, Bash, DENY",
            "PLAN,    false, Write, DENY",
            "BYPASS,  true,  Read, ALLOW",
            "BYPASS,  false, Bash, ALLOW",
            "BYPASS,  false, Write, ALLOW",
            "AUTO,    true,  Read, ALLOW",
            "AUTO,    false, Bash, ASK",
            "AUTO,    false, Write, ASK",
            "AUTO,    true,  Grep, ALLOW",
    })
    void decisionMatrix(PermissionMode mode, boolean readOnly, String toolName, Decision expected) {
        Tool tool = stubTool(toolName, readOnly);
        ToolInvocation invocation = ToolInvocation.create(
                new ToolUseId("u1"), toolName, ToolArguments.empty());

        assertThat(policy.decide(invocation, tool, mode)).isEqualTo(expected);
    }

    @Test
    void defaultModeReadOnlyAlwaysAllow() {
        Tool readOnly = stubTool("Read", true);
        ToolInvocation inv = ToolInvocation.create(new ToolUseId("u1"), "Read", ToolArguments.empty());

        assertThat(policy.decide(inv, readOnly, PermissionMode.DEFAULT)).isEqualTo(Decision.ALLOW);
    }

    @Test
    void autoModeAllowsRegisteredSafelist() {
        Tool customTool = stubTool("CustomSafe", false);
        DefaultPermissionPolicy autoSafelisted =
                new DefaultPermissionPolicy(java.util.Set.of("CustomSafe"));

        ToolInvocation inv = ToolInvocation.create(
                new ToolUseId("u1"), "CustomSafe", ToolArguments.empty());

        assertThat(autoSafelisted.decide(inv, customTool, PermissionMode.AUTO))
                .isEqualTo(Decision.ALLOW);
    }

    private static Tool stubTool(String name, boolean readOnly) {
        return new Tool() {
            @Override public String name() { return name; }
            @Override public String description() { return name; }
            @Override public String inputSchema() { return "{}"; }
            @Override public boolean isReadOnly() { return readOnly; }
            @Override public ToolResult execute(ToolArguments args, ExecutionContext ctx) {
                return ToolResult.ok("stub");
            }
        };
    }
}
