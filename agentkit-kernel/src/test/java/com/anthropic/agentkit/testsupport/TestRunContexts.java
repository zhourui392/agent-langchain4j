package com.anthropic.agentkit.testsupport;

import com.anthropic.agentkit.domain.agent.AgentBudget;
import com.anthropic.agentkit.domain.agent.AgentRunContext;
import com.anthropic.agentkit.domain.conversation.CancellationToken;
import com.anthropic.agentkit.domain.conversation.Conversation;

import java.nio.file.Path;

/** Explicit run-scope fixtures for kernel tests. */
public final class TestRunContexts {

    private TestRunContexts() {
    }

    public static AgentRunContext runContext(Conversation conversation) {
        return runContext(conversation, new CancellationToken());
    }

    public static AgentRunContext runContext(Conversation conversation,
                                             CancellationToken cancellation) {
        return runContext(conversation, cancellation, AgentBudget.unlimited());
    }

    public static AgentRunContext runContext(Conversation conversation,
                                             CancellationToken cancellation,
                                             AgentBudget budget) {
        return AgentRunContext.create(
                conversation.sessionId(), Path.of("."), cancellation, budget);
    }
}
