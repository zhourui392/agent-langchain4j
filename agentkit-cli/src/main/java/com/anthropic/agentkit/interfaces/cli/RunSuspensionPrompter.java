package com.anthropic.agentkit.interfaces.cli;

import com.anthropic.agentkit.application.io.TerminalIo;
import com.anthropic.agentkit.domain.agent.AgentRunResult;
import com.anthropic.agentkit.domain.suspension.ResumeCommand;
import com.anthropic.agentkit.domain.suspension.ResumeToken;
import com.anthropic.agentkit.domain.suspension.RunSuspension;

import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

/** CLI host adapter for the same typed suspension contract usable by non-terminal hosts. */
final class RunSuspensionPrompter {

    private final TerminalIo terminal;

    RunSuspensionPrompter(TerminalIo terminal) {
        this.terminal = Objects.requireNonNull(terminal, "terminal");
    }

    Optional<ResumeCommand> prompt(AgentRunResult result) {
        Objects.requireNonNull(result, "result");
        Optional<RunSuspension> suspension = result.suspension();
        Optional<ResumeToken> token = result.resumeToken();
        if (suspension.isEmpty() || token.isEmpty()) {
            return Optional.empty();
        }
        return switch (suspension.orElseThrow()) {
            case RunSuspension.WaitingForApproval approval ->
                    promptApproval(approval, token.orElseThrow());
            case RunSuspension.WaitingForInput input ->
                    promptInput(input, token.orElseThrow());
        };
    }

    private Optional<ResumeCommand> promptApproval(
            RunSuspension.WaitingForApproval approval, ResumeToken token) {
        String names = approval.request().invocations().stream()
                .map(item -> item.request().toolName())
                .collect(Collectors.joining(", "));
        Optional<String> answer = terminal.readLine(
                "Approve tool batch [" + names + "]? [y/N] ");
        if (answer.isEmpty()) {
            return Optional.empty();
        }
        boolean approved = switch (answer.orElseThrow().trim().toLowerCase()) {
            case "y", "yes" -> true;
            default -> false;
        };
        return Optional.of(approved
                ? ResumeCommand.approve(token) : ResumeCommand.deny(token));
    }

    private Optional<ResumeCommand> promptInput(
            RunSuspension.WaitingForInput input, ResumeToken token) {
        while (true) {
            Optional<String> answer = terminal.readLine(input.request().prompt() + " ");
            if (answer.isEmpty()) {
                return Optional.empty();
            }
            if (!answer.orElseThrow().isBlank()) {
                return Optional.of(ResumeCommand.answer(token, answer.orElseThrow()));
            }
            terminal.writeError("answer must not be blank");
        }
    }
}
