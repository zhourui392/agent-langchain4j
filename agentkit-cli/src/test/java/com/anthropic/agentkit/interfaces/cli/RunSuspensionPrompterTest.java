package com.anthropic.agentkit.interfaces.cli;

import com.anthropic.agentkit.domain.agent.AgentRunResult;
import com.anthropic.agentkit.domain.agent.AgentUsage;
import com.anthropic.agentkit.domain.agent.BudgetConsumption;
import com.anthropic.agentkit.domain.agent.RunId;
import com.anthropic.agentkit.domain.agent.WorkspaceId;
import com.anthropic.agentkit.domain.conversation.SessionId;
import com.anthropic.agentkit.domain.message.AiMessage;
import com.anthropic.agentkit.domain.permission.Decision;
import com.anthropic.agentkit.domain.suspension.ApprovalDecision;
import com.anthropic.agentkit.domain.suspension.ApprovalRequest;
import com.anthropic.agentkit.domain.suspension.InputRequest;
import com.anthropic.agentkit.domain.suspension.PlannedToolInvocation;
import com.anthropic.agentkit.domain.suspension.ResumeCommand;
import com.anthropic.agentkit.domain.suspension.ResumeToken;
import com.anthropic.agentkit.domain.suspension.RunSuspension;
import com.anthropic.agentkit.domain.suspension.SuspensionId;
import com.anthropic.agentkit.domain.suspension.SuspensionScope;
import com.anthropic.agentkit.domain.tool.ToolUseId;
import com.anthropic.agentkit.domain.tool.ToolUseRequest;
import com.anthropic.agentkit.testsupport.io.ScriptedTerminalIo;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RunSuspensionPrompterTest {

    @Test
    void mapsApprovalAnswersToTypedResumeCommands() {
        ScriptedTerminalIo terminal = ScriptedTerminalIo.builder()
                .input("yes").input("no").build();
        RunSuspensionPrompter prompter = new RunSuspensionPrompter(terminal);

        ResumeCommand approved = prompter.prompt(result(approval())).orElseThrow();
        ResumeCommand denied = prompter.prompt(result(approval())).orElseThrow();

        assertThat(approved).isEqualTo(
                new ResumeCommand.Approval(TOKEN, ApprovalDecision.APPROVE));
        assertThat(denied).isEqualTo(
                new ResumeCommand.Approval(TOKEN, ApprovalDecision.DENY));
    }

    @Test
    void rejectsBlankInputBeforeReturningAnswer() {
        ScriptedTerminalIo terminal = ScriptedTerminalIo.builder()
                .input("  ").input("main").build();
        RunSuspensionPrompter prompter = new RunSuspensionPrompter(terminal);

        ResumeCommand command = prompter.prompt(result(input())).orElseThrow();

        assertThat(command).isEqualTo(ResumeCommand.answer(TOKEN, "main"));
        assertThat(terminal.errorOutput()).contains("must not be blank");
    }

    @Test
    void endOfInputLeavesSuspensionPending() {
        ScriptedTerminalIo terminal = ScriptedTerminalIo.builder().build();

        assertThat(new RunSuspensionPrompter(terminal).prompt(result(input())))
                .isEmpty();
    }

    private static AgentRunResult result(RunSuspension suspension) {
        return AgentRunResult.suspended(
                RunId.of("origin"), suspension, TOKEN,
                AgentUsage.zero(), BudgetConsumption.zero());
    }

    private static RunSuspension.WaitingForApproval approval() {
        ToolUseRequest tool = new ToolUseRequest(
                new ToolUseId("write-1"), "Write", "{}");
        return new RunSuspension.WaitingForApproval(
                SuspensionId.of("approval"), SCOPE,
                new ApprovalRequest(List.of(
                        new PlannedToolInvocation(tool, Decision.ASK))),
                AiMessage.of("write", List.of(tool)));
    }

    private static RunSuspension.WaitingForInput input() {
        return new RunSuspension.WaitingForInput(
                SuspensionId.of("input"), SCOPE,
                InputRequest.of("Which branch?"));
    }

    private static final ResumeToken TOKEN = ResumeToken.of("resume-secret");
    private static final SuspensionScope SCOPE = new SuspensionScope(
            SessionId.of("session"), WorkspaceId.of("workspace"), RunId.of("origin"));
}
