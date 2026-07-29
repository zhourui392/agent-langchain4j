package com.anthropic.agentkit.application.cli;

import com.anthropic.agentkit.application.recovery.RecoveredRun;
import com.anthropic.agentkit.application.recovery.RunEventResumer;
import com.anthropic.agentkit.domain.agent.RunId;
import com.anthropic.agentkit.domain.conversation.Conversation;
import com.anthropic.agentkit.domain.conversation.SessionId;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/** Owns the CLI's replaceable active conversation without depending on terminal UI. */
public final class CliSession {

    private final AtomicReference<Conversation> active =
            new AtomicReference<>(new Conversation(SessionId.fresh()));
    private final RunEventResumer resumer;

    public CliSession(RunEventResumer resumer) {
        this.resumer = Objects.requireNonNull(resumer, "resumer");
    }

    public Conversation activeConversation() {
        return active.get();
    }

    public Conversation clear() {
        Conversation conversation = new Conversation(SessionId.fresh());
        active.set(conversation);
        return conversation;
    }

    public RecoveredRun resume(RunId runId) {
        RecoveredRun recovered = resumer.resume(Objects.requireNonNull(runId, "runId"));
        active.set(recovered.conversation());
        return recovered;
    }
}
