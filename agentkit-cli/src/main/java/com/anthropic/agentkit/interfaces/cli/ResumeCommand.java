package com.anthropic.agentkit.interfaces.cli;

import com.anthropic.agentkit.application.cli.CliSession;
import com.anthropic.agentkit.application.recovery.RecoveredRun;
import com.anthropic.agentkit.application.recovery.RecoveredToolInvocation;
import com.anthropic.agentkit.application.recovery.RunEventResumer.RunNotFoundException;
import com.anthropic.agentkit.domain.agent.RunId;

import java.util.List;
import java.util.Objects;

public final class ResumeCommand implements SlashCommand {

    private final CliSession session;

    public ResumeCommand(CliSession session) {
        this.session = Objects.requireNonNull(session, "session");
    }

    @Override
    public String name() {
        return "resume";
    }

    @Override
    public String usage() {
        return "resume <run-id>";
    }

    @Override
    public String description() {
        return "Resume a run from persisted events";
    }

    @Override
    public String execute(List<String> args) {
        if (args.size() != 1) {
            return "usage: /" + usage();
        }
        RunId runId = RunId.of(args.getFirst());
        try {
            return render(runId, session.resume(runId));
        } catch (RunNotFoundException notFound) {
            return notFound.getMessage();
        }
    }

    private static String render(RunId runId, RecoveredRun recovered) {
        StringBuilder output = new StringBuilder("(resumed run ")
                .append(runId).append(", session ")
                .append(recovered.conversation().sessionId()).append(')');
        recovered.invocations().stream()
                .filter(invocation -> invocation.status()
                        != com.anthropic.agentkit.application.recovery.RecoveryStatus.SETTLED)
                .forEach(invocation -> appendUnsettled(output, invocation));
        return output.toString();
    }

    private static void appendUnsettled(
            StringBuilder output, RecoveredToolInvocation invocation) {
        output.append("\n  ").append(invocation.request().toolName())
                .append(' ').append(invocation.request().id())
                .append(": ").append(invocation.status())
                .append("; reconciliation required before retry");
    }
}
