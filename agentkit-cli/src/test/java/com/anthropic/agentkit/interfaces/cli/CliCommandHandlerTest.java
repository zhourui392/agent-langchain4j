package com.anthropic.agentkit.interfaces.cli;

import com.anthropic.agentkit.application.cli.CliSession;
import com.anthropic.agentkit.application.recovery.RunEventResumer;
import com.anthropic.agentkit.domain.agent.AgentEntryPoint;
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
import com.anthropic.agentkit.testsupport.io.ScriptedTerminalIo;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class CliCommandHandlerTest {

    private static final RunId RESUME_RUN = RunId.of("handler-resume-run");

    @Test
    void clearRunsThroughRegisteredCommandAndChangesTheNextUserConversation() {
        CliSession session = new CliSession(new RunEventResumer(RunEventStore.none()));
        Conversation original = session.activeConversation();
        CapturingEntryPoint agent = new CapturingEntryPoint();
        ScriptedTerminalIo terminal = ScriptedTerminalIo.builder().build();
        CliCommandHandler handler = new CliCommandHandler(
                SlashCommands.standard(session), session, agent, terminal);

        handler.handle("/clear");
        handler.handle("hello");

        assertThat(terminal.output()).contains("conversation cleared");
        assertThat(agent.request.get().conversation()).isNotSameAs(original);
        assertThat(agent.request.get().conversation().messages())
                .containsExactly(UserMessage.of("hello"));
    }

    @Test
    void unknownCommandIsVisibleAndDoesNotInvokeAgent() {
        CliSession session = new CliSession(new RunEventResumer(RunEventStore.none()));
        CapturingEntryPoint agent = new CapturingEntryPoint();
        ScriptedTerminalIo terminal = ScriptedTerminalIo.builder().build();
        CliCommandHandler handler = new CliCommandHandler(
                SlashCommands.standard(session), session, agent, terminal);

        handler.handle("/missing");

        assertThat(terminal.errorOutput()).contains("unknown command: /missing");
        assertThat(agent.request).hasValue(null);
    }

    @Test
    void resumeRunsThroughTheCliAndRendersUnknownInvocation() {
        CliSession session = new CliSession(new RunEventResumer(new IncompleteRunStore()));
        ScriptedTerminalIo terminal = ScriptedTerminalIo.builder().build();
        CliCommandHandler handler = new CliCommandHandler(
                SlashCommands.standard(session), session,
                new CapturingEntryPoint(), terminal);

        handler.handle("/resume " + RESUME_RUN.value());

        assertThat(terminal.output()).contains(
                "resumed run", "Read", "read-handler", "UNKNOWN", "reconciliation required");
        assertThat(session.activeConversation().sessionId())
                .isEqualTo(SessionId.of("handler-resume-session"));
    }

    private static RunEventMetadata metadata(long sequence) {
        return new RunEventMetadata(
                RunEvent.CURRENT_SCHEMA_VERSION, RESUME_RUN,
                SessionId.of("handler-resume-session"), WorkspaceId.of("handler-workspace"),
                sequence, Instant.parse("2026-07-29T12:00:00Z").plusSeconds(sequence));
    }

    private static final class CapturingEntryPoint
            implements AgentEntryPoint<CliAgentRequest, CliAgentResult> {
        private final AtomicReference<CliAgentRequest> request = new AtomicReference<>();

        @Override
        public Class<CliAgentRequest> requestType() {
            return CliAgentRequest.class;
        }

        @Override
        public Class<CliAgentResult> resultType() {
            return CliAgentResult.class;
        }

        @Override
        public CliAgentResult invoke(CliAgentRequest request) {
            this.request.set(request);
            return CliAgentResult.empty();
        }
    }

    private static final class IncompleteRunStore implements RunEventStore {
        @Override
        public void append(RunEvent event) {
            throw new AssertionError("CLI resume must not append or replay");
        }

        @Override
        public List<RunEvent> load(RunId runId) {
            ToolUseId id = new ToolUseId("read-handler");
            AiMessage assistant = AiMessage.of("reading", List.of(
                    new ToolUseRequest(id, "Read", "{\"path\":\"a.txt\"}")));
            return List.of(
                    new RunEvent.RunStarted(metadata(1),
                            List.of(UserMessage.of("inspect")), Optional.empty()),
                    new RunEvent.AssistantTurnReceived(metadata(2), assistant),
                    new RunEvent.ToolInvocationStarted(metadata(3), id));
        }
    }
}
