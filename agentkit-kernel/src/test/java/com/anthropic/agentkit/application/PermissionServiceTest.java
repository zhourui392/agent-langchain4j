package com.anthropic.agentkit.application;

import com.anthropic.agentkit.application.InteractivePrompter.UserPermissionResponse;
import com.anthropic.agentkit.domain.agent.RunId;
import com.anthropic.agentkit.domain.permission.Decision;
import com.anthropic.agentkit.domain.permission.PermissionMode;
import com.anthropic.agentkit.domain.tool.ExecutionContext;
import com.anthropic.agentkit.domain.tool.Tool;
import com.anthropic.agentkit.domain.tool.ToolArguments;
import com.anthropic.agentkit.domain.tool.ToolInvocation;
import com.anthropic.agentkit.domain.tool.ToolResult;
import com.anthropic.agentkit.domain.tool.ToolUseId;
import com.anthropic.agentkit.infrastructure.permission.DefaultPermissionPolicy;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PermissionServiceTest {

    private static final RunId RUN_ID = RunId.of("permission-test-run");
    private final Tool readTool = stubTool("Read", true);
    private final Tool writeTool = stubTool("Write", false);

    @Test
    void allowSkipsPrompter() {
        InteractivePrompter prompter = mock(InteractivePrompter.class);
        PermissionService service = new PermissionService(
                new DefaultPermissionPolicy(), prompter, PermissionMode.DEFAULT);

        Decision decision = service.check(RUN_ID, invocationFor(readTool), readTool);

        assertThat(decision).isEqualTo(Decision.ALLOW);
        verify(prompter, never()).ask(any(), any());
    }

    @Test
    void denySkipsPrompter() {
        InteractivePrompter prompter = mock(InteractivePrompter.class);
        PermissionService service = new PermissionService(
                new DefaultPermissionPolicy(), prompter, PermissionMode.PLAN);

        Decision decision = service.check(RUN_ID, invocationFor(writeTool), writeTool);

        assertThat(decision).isEqualTo(Decision.DENY);
        verify(prompter, never()).ask(any(), any());
    }

    @Test
    void askInvokesPrompterAndReturnsDecision() {
        InteractivePrompter prompter = mock(InteractivePrompter.class);
        when(prompter.ask(any(), any())).thenReturn(UserPermissionResponse.ALLOW_ONCE);
        PermissionService service = new PermissionService(
                new DefaultPermissionPolicy(), prompter, PermissionMode.DEFAULT);

        Decision decision = service.check(RUN_ID, invocationFor(writeTool), writeTool);

        assertThat(decision).isEqualTo(Decision.ALLOW);
        verify(prompter, times(1)).ask(any(), any());
    }

    @Test
    void denyAnswerFromPrompterPropagatesAsDeny() {
        InteractivePrompter prompter = mock(InteractivePrompter.class);
        when(prompter.ask(any(), any())).thenReturn(UserPermissionResponse.DENY);
        PermissionService service = new PermissionService(
                new DefaultPermissionPolicy(), prompter, PermissionMode.DEFAULT);

        Decision decision = service.check(RUN_ID, invocationFor(writeTool), writeTool);

        assertThat(decision).isEqualTo(Decision.DENY);
    }

    @Test
    void cachesAllowAlwaysDecisionsPerSession() {
        InteractivePrompter prompter = mock(InteractivePrompter.class);
        when(prompter.ask(any(), any())).thenReturn(UserPermissionResponse.ALLOW_ALWAYS);
        PermissionService service = new PermissionService(
                new DefaultPermissionPolicy(), prompter, PermissionMode.DEFAULT);

        Decision first = service.check(RUN_ID, invocationFor(writeTool), writeTool);
        Decision second = service.check(RUN_ID, invocationFor(writeTool), writeTool);
        Decision third = service.check(RUN_ID, invocationFor(writeTool), writeTool);

        assertThat(first).isEqualTo(Decision.ALLOW);
        assertThat(second).isEqualTo(Decision.ALLOW);
        assertThat(third).isEqualTo(Decision.ALLOW);
        verify(prompter, times(1)).ask(any(), any());
    }

    private static ToolInvocation invocationFor(Tool tool) {
        return ToolInvocation.create(new ToolUseId("u-" + tool.name()), tool.name(), ToolArguments.empty());
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
