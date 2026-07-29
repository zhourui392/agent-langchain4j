package com.anthropic.agentkit.interfaces.cli;

import com.anthropic.agentkit.application.cli.CliSession;
import com.anthropic.agentkit.application.recovery.RunEventResumer;
import com.anthropic.agentkit.domain.agent.AgentEntryPoint;
import com.anthropic.agentkit.domain.agent.RunId;
import com.anthropic.agentkit.domain.conversation.Conversation;
import com.anthropic.agentkit.domain.message.UserMessage;
import com.anthropic.agentkit.domain.port.RunEventStore;
import com.anthropic.agentkit.testsupport.io.ScriptedTerminalIo;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class CliCommandHandlerTest {

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
}
