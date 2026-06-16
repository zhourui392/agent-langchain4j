package com.anthropic.agentkit.application;

import com.anthropic.agentkit.application.InteractivePrompter.UserPermissionResponse;
import com.anthropic.agentkit.application.io.TerminalIo.PromptAnswer;
import com.anthropic.agentkit.domain.tool.ToolArguments;
import com.anthropic.agentkit.domain.tool.ToolInvocation;
import com.anthropic.agentkit.domain.tool.ToolUseId;
import com.anthropic.agentkit.testsupport.FakeTool;
import com.anthropic.agentkit.testsupport.io.ScriptedTerminalIo;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TerminalIoPrompterTest {

    private final ToolInvocation invocation = ToolInvocation.create(
            new ToolUseId("u1"), "Bash", ToolArguments.empty());
    private final FakeTool bash = FakeTool.returning("Bash", "ok");

    @Test
    void allowOnceMapsToAllowOnceResponse() {
        ScriptedTerminalIo io = ScriptedTerminalIo.builder()
                .answer(PromptAnswer.ALLOW_ONCE)
                .build();
        TerminalIoPrompter prompter = new TerminalIoPrompter(io);

        assertThat(prompter.ask(invocation, bash)).isEqualTo(UserPermissionResponse.ALLOW_ONCE);
    }

    @Test
    void allowAlwaysMapsToAllowAlwaysResponse() {
        ScriptedTerminalIo io = ScriptedTerminalIo.builder()
                .answer(PromptAnswer.ALLOW_ALWAYS)
                .build();
        TerminalIoPrompter prompter = new TerminalIoPrompter(io);

        assertThat(prompter.ask(invocation, bash)).isEqualTo(UserPermissionResponse.ALLOW_ALWAYS);
    }

    @Test
    void denyMapsToDenyResponse() {
        ScriptedTerminalIo io = ScriptedTerminalIo.builder()
                .answer(PromptAnswer.DENY)
                .build();
        TerminalIoPrompter prompter = new TerminalIoPrompter(io);

        assertThat(prompter.ask(invocation, bash)).isEqualTo(UserPermissionResponse.DENY);
    }

    @Test
    void questionIdentifiesToolName() {
        ScriptedTerminalIo io = ScriptedTerminalIo.builder()
                .answer(PromptAnswer.ALLOW_ONCE)
                .build();
        TerminalIoPrompter prompter = new TerminalIoPrompter(io);

        prompter.ask(invocation, bash);

        assertThat(io.capturedPrompts()).singleElement().asString().contains("Bash");
    }
}
