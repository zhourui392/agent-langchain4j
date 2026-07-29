package com.anthropic.agentkit.application.cli;

import com.anthropic.agentkit.application.recovery.RecoveredRun;
import com.anthropic.agentkit.application.recovery.RecoveryStatus;
import com.anthropic.agentkit.application.recovery.RunEventResumer;
import com.anthropic.agentkit.domain.agent.RunId;
import com.anthropic.agentkit.domain.agent.WorkspaceId;
import com.anthropic.agentkit.domain.conversation.Conversation;
import com.anthropic.agentkit.domain.conversation.SessionId;
import com.anthropic.agentkit.domain.message.AiMessage;
import com.anthropic.agentkit.domain.message.UserMessage;
import com.anthropic.agentkit.domain.port.RunEventStore;
import com.anthropic.agentkit.domain.run.RunEvent;
import com.anthropic.agentkit.domain.run.RunEventMetadata;
import com.anthropic.agentkit.domain.tool.ToolUseId;
import com.anthropic.agentkit.domain.tool.ToolUseRequest;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class CliSessionTest {

    private static final RunId RUN = RunId.of("resume-run");

    @Test
    void clearReplacesActiveConversationWithFreshSession() {
        CliSession session = new CliSession(new RunEventResumer(RunEventStore.none()));
        Conversation original = session.activeConversation();
        original.append(UserMessage.of("old input"));

        Conversation cleared = session.clear();

        assertThat(cleared).isSameAs(session.activeConversation());
        assertThat(cleared).isNotSameAs(original);
        assertThat(cleared.sessionId()).isNotEqualTo(original.sessionId());
        assertThat(cleared.messages()).isEmpty();
    }

    @Test
    void resumeReplacesActiveConversationFromRunFactsWithoutReexecution() {
        CliSession session = new CliSession(new RunEventResumer(
                new FixedRunEventStore(incompleteToolRun())));

        RecoveredRun recovered = session.resume(RUN);

        assertThat(session.activeConversation()).isSameAs(recovered.conversation());
        assertThat(recovered.conversation().messages())
                .extracting(message -> message.text())
                .containsExactly("inspect", "reading",
                        "tool outcome unknown after interrupted run; reconciliation required");
        assertThat(recovered.invocations()).singleElement()
                .extracting(invocation -> invocation.status())
                .isEqualTo(RecoveryStatus.UNKNOWN);
    }

    private static List<RunEvent> incompleteToolRun() {
        ToolUseId toolUseId = new ToolUseId("read-1");
        AiMessage assistant = AiMessage.of("reading", List.of(
                new ToolUseRequest(toolUseId, "Read", "{\"path\":\"a.txt\"}")));
        return List.of(
                new RunEvent.RunStarted(metadata(1),
                        List.of(UserMessage.of("inspect")), Optional.empty()),
                new RunEvent.AssistantTurnReceived(metadata(2), assistant),
                new RunEvent.ToolInvocationStarted(metadata(3), toolUseId));
    }

    private static RunEventMetadata metadata(long sequence) {
        return new RunEventMetadata(
                RunEvent.CURRENT_SCHEMA_VERSION, RUN, SessionId.of("resume-session"),
                WorkspaceId.of("resume-workspace"), sequence,
                Instant.parse("2026-07-29T10:00:00Z").plusSeconds(sequence));
    }

    private record FixedRunEventStore(List<RunEvent> events) implements RunEventStore {
        private FixedRunEventStore {
            events = List.copyOf(events);
        }

        @Override
        public void append(RunEvent event) {
            throw new AssertionError("resume must not append or replay events");
        }

        @Override
        public List<RunEvent> load(RunId runId) {
            return RUN.equals(runId) ? events : List.of();
        }
    }
}
