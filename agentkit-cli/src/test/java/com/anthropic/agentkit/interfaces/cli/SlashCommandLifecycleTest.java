package com.anthropic.agentkit.interfaces.cli;

import com.anthropic.agentkit.application.cli.CliSession;
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

class SlashCommandLifecycleTest {

    private static final RunId RUN = RunId.of("resume-run");

    @Test
    void helpIsGeneratedFromExactlyTheRegisteredCommands() {
        SlashCommandParser parser = SlashCommands.standard(session(RunEventStore.none()));

        String help = execute(parser, "/help");

        assertThat(parser.commands()).extracting(SlashCommand::name)
                .containsExactly("help", "clear", "resume");
        assertThat(parser.commands()).allSatisfy(command -> {
            assertThat(command.usage()).isNotBlank();
            assertThat(command.description()).isNotBlank();
            assertThat(help).contains("/" + command.usage(), command.description());
            assertThat(parser.parse("/" + command.name()))
                    .isInstanceOf(SlashCommandParser.CommandInvocation.class);
        });
        assertThat(help).contains("exit, quit");
    }

    @Test
    void clearCommandActuallyReplacesTheActiveConversation() {
        CliSession session = session(RunEventStore.none());
        SlashCommandParser parser = SlashCommands.standard(session);
        Conversation original = session.activeConversation();
        original.append(UserMessage.of("old input"));

        String output = execute(parser, "/clear");

        assertThat(output).contains("conversation cleared");
        assertThat(session.activeConversation()).isNotSameAs(original);
        assertThat(session.activeConversation().messages()).isEmpty();
    }

    @Test
    void resumeCommandUsesEventProjectionAndDisplaysUnknownInvocation() {
        CliSession session = session(new FixedRunEventStore(incompleteToolRun()));
        SlashCommandParser parser = SlashCommands.standard(session);

        String output = execute(parser, "/resume " + RUN.value());

        assertThat(output).contains("resumed run " + RUN.value(), "Read", "read-1",
                "UNKNOWN", "reconciliation required");
        assertThat(session.activeConversation().sessionId())
                .isEqualTo(SessionId.of("resume-session"));
    }

    @Test
    void resumeCommandReportsMissingRunWithoutClaimingSuccess() {
        SlashCommandParser parser = SlashCommands.standard(session(RunEventStore.none()));

        String output = execute(parser, "/resume absent-run");

        assertThat(output).contains("run not found", "absent-run").doesNotContain("resumed run");
    }

    private static CliSession session(RunEventStore store) {
        return new CliSession(new RunEventResumer(store));
    }

    private static String execute(SlashCommandParser parser, String input) {
        SlashCommandParser.CommandInvocation invocation =
                (SlashCommandParser.CommandInvocation) parser.parse(input);
        return invocation.command().execute(invocation.args());
    }

    private static List<RunEvent> incompleteToolRun() {
        ToolUseId id = new ToolUseId("read-1");
        AiMessage assistant = AiMessage.of("reading", List.of(
                new ToolUseRequest(id, "Read", "{\"path\":\"a.txt\"}")));
        return List.of(
                new RunEvent.RunStarted(metadata(1),
                        List.of(UserMessage.of("inspect")), Optional.empty()),
                new RunEvent.AssistantTurnReceived(metadata(2), assistant),
                new RunEvent.ToolInvocationStarted(metadata(3), id));
    }

    private static RunEventMetadata metadata(long sequence) {
        return new RunEventMetadata(
                RunEvent.CURRENT_SCHEMA_VERSION, RUN, SessionId.of("resume-session"),
                WorkspaceId.of("resume-workspace"), sequence,
                Instant.parse("2026-07-29T11:00:00Z").plusSeconds(sequence));
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
