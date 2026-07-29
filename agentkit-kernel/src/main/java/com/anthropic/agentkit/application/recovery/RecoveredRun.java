package com.anthropic.agentkit.application.recovery;

import com.anthropic.agentkit.domain.agent.AgentRunResult;
import com.anthropic.agentkit.domain.conversation.Conversation;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Safe run projection returned by resume; it contains no executable tool capability. */
public record RecoveredRun(
        Conversation conversation,
        Optional<AgentRunResult> terminalResult,
        List<RecoveredToolInvocation> invocations) {

    public RecoveredRun {
        Objects.requireNonNull(conversation, "conversation");
        terminalResult = Objects.requireNonNull(terminalResult, "terminalResult");
        invocations = List.copyOf(Objects.requireNonNull(invocations, "invocations"));
    }
}
