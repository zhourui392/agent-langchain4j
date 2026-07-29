package com.anthropic.agentkit.infrastructure.memory;

import com.anthropic.agentkit.domain.agent.AgentUsage;
import com.anthropic.agentkit.domain.agent.BudgetConsumption;
import com.anthropic.agentkit.domain.agent.RunId;
import com.anthropic.agentkit.domain.agent.StopReason;
import com.anthropic.agentkit.domain.agent.WorkspaceId;
import com.anthropic.agentkit.domain.conversation.CompactionBoundary;
import com.anthropic.agentkit.domain.conversation.SessionId;
import com.anthropic.agentkit.domain.message.AiMessage;
import com.anthropic.agentkit.domain.message.ToolResultMessage;
import com.anthropic.agentkit.domain.message.UserMessage;
import com.anthropic.agentkit.domain.run.RunEvent;
import com.anthropic.agentkit.domain.run.RunEventMetadata;
import com.anthropic.agentkit.domain.tool.ToolResult;
import com.anthropic.agentkit.domain.tool.ToolResultStatus;
import com.anthropic.agentkit.domain.tool.ToolUseId;
import com.anthropic.agentkit.domain.tool.ToolUseRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FileRunEventStoreTest {

    private static final RunId RUN = RunId.of("run-events-1");

    @Test
    void appendsRunEventsWithoutRewritingExistingLog(@TempDir Path directory) throws IOException {
        FileRunEventStore store = new FileRunEventStore(directory);
        store.append(started(1));
        byte[] prefix = Files.readAllBytes(store.pathFor(RUN));

        store.append(new RunEvent.AssistantTurnReceived(
                metadata(2), AiMessage.text("done")));

        byte[] complete = Files.readAllBytes(store.pathFor(RUN));
        assertThat(complete).startsWith(prefix).hasSizeGreaterThan(prefix.length);
        assertThat(store.load(RUN)).hasSize(2);
    }

    @Test
    void ignoresOnlyTruncatedFinalRecordAndRejectsMidLogCorruption(
            @TempDir Path directory) throws IOException {
        FileRunEventStore store = new FileRunEventStore(directory);
        store.append(started(1));
        store.append(new RunEvent.AssistantTurnReceived(
                metadata(2), AiMessage.text("done")));
        Path file = store.pathFor(RUN);
        String valid = Files.readString(file);

        Files.writeString(file, valid + "{\"schemaVersion\":1");
        assertThat(store.load(RUN)).hasSize(2);

        Files.writeString(file, firstLine(valid) + "{broken-json}\n" + secondLine(valid));
        assertThatThrownBy(() -> store.load(RUN))
                .isInstanceOf(RunEventCorruptionException.class)
                .hasMessageContaining("mid-log");
    }

    @Test
    void eventSequenceIsMonotonicPerRun(@TempDir Path directory) {
        FileRunEventStore store = new FileRunEventStore(directory);
        store.append(started(1));

        assertThatThrownBy(() -> store.append(new RunEvent.AssistantTurnReceived(
                metadata(3), AiMessage.text("gap"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("expected sequence 2");
        assertThat(store.load(RUN)).hasSize(1);
    }

    @Test
    void rejectsScopeChangesWithinRun(@TempDir Path directory) {
        FileRunEventStore store = new FileRunEventStore(directory);
        store.append(started(1));
        RunEventMetadata changedSession = new RunEventMetadata(
                RunEvent.CURRENT_SCHEMA_VERSION, RUN, SessionId.of("other-session"),
                WorkspaceId.of("workspace-events-1"), 2,
                Instant.parse("2026-07-29T00:00:02Z"));

        assertThatThrownBy(() -> store.append(new RunEvent.LlmCallStarted(
                changedSession, 1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("scope changed");
    }

    @Test
    void redactsSensitiveToolArgumentsAndTerminalPayloadBeforeWritingEventLog(
            @TempDir Path directory) throws IOException {
        FileRunEventStore store = new FileRunEventStore(directory);
        AiMessage request = AiMessage.of("calling", List.of(new ToolUseRequest(
                new ToolUseId("secret-tool"), "RemoteCall",
                "{\"user\":\"alice\",\"api_key\":\"sk-live-secret\","
                        + "\"nested\":{\"password\":\"db-password\"}}")));
        store.append(new RunEvent.AssistantTurnReceived(metadata(1), request));
        store.append(stoppedWithPayload(2, request));

        String durable = Files.readString(store.pathFor(RUN));
        List<RunEvent> recovered = store.load(RUN);

        assertThat(durable).doesNotContain("sk-live-secret", "db-password", "terminal-secret")
                .contains(RunEventDataPolicy.REDACTED, "alice", "kept");
        RunEvent.RunStopped stopped = (RunEvent.RunStopped) recovered.getLast();
        assertThat(stopped.structuredOutput().orElseThrow())
                .containsEntry("apiKey", RunEventDataPolicy.REDACTED)
                .containsEntry("safe", "kept");
    }

    @Test
    void boundsLegacyLargeToolResultInRunStarted(@TempDir Path directory) throws IOException {
        FileRunEventStore store = new FileRunEventStore(directory);
        String legacyOutput = "x".repeat(RunEventDataPolicy.MAX_TEXT_CHARACTERS * 3);
        RunEvent.RunStarted event = new RunEvent.RunStarted(
                metadata(1), List.of(ToolResultMessage.of(
                        new ToolUseId("legacy-tool"), legacyOutput)), Optional.empty());

        store.append(event);

        RunEvent.RunStarted recovered = (RunEvent.RunStarted) store.load(RUN).getFirst();
        ToolResultMessage result = (ToolResultMessage) recovered.initialMessages().getFirst();
        assertThat(result.text()).hasSize(RunEventDataPolicy.MAX_TEXT_CHARACTERS)
                .endsWith("artifact unavailable]");
        assertThat(result.metadata())
                .containsEntry("agentkit.persistence.disposition", "truncated")
                .containsEntry("agentkit.persistence.artifact", "omitted");
        assertThat(Files.size(store.pathFor(RUN)))
                .isLessThan(RunEventDataPolicy.MAX_TEXT_CHARACTERS + 2_000L);
    }

    @Test
    void versionOneEventSchemaRoundTrips() throws IOException {
        CompactionBoundary boundary = new CompactionBoundary(0, 1, 12, 1, "summary");
        List<RunEvent> events = List.of(
                started(1),
                new RunEvent.LlmCallStarted(metadata(2), 1),
                new RunEvent.AssistantTurnReceived(metadata(3), AiMessage.text("done")),
                new RunEvent.ToolInvocationStarted(metadata(4), new ToolUseId("tool-1")),
                new RunEvent.ToolInvocationSettled(metadata(5), new ToolUseId("tool-1"),
                        ToolResult.of(ToolResultStatus.SUCCESS, "body", Map.of("source", "test"))),
                new RunEvent.CompactionCompleted(
                        metadata(6), boundary, List.of(UserMessage.of("recent"))),
                stoppedWithSafePayload(7, AiMessage.text("finished")));

        List<RunEvent> decoded = events.stream().map(FileRunEventStoreTest::roundTrip).toList();

        assertThat(decoded).containsExactlyElementsOf(events);
    }

    @Test
    void rejectsUnsupportedFutureSchemaVersion() throws IOException {
        String future = RunEventJsonCodec.toJson(started(1))
                .replace("\"schemaVersion\":1", "\"schemaVersion\":2");

        assertThatThrownBy(() -> RunEventJsonCodec.fromJson(future))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("unsupported run event schema version: 2");
    }

    @Test
    void doesNotTreatCompleteFutureSchemaAsTruncatedTail(
            @TempDir Path directory) throws IOException {
        FileRunEventStore store = new FileRunEventStore(directory);
        String future = RunEventJsonCodec.toJson(started(1))
                .replace("\"schemaVersion\":1", "\"schemaVersion\":2");
        Files.createDirectories(directory);
        Files.writeString(store.pathFor(RUN), future);

        assertThatThrownBy(() -> store.load(RUN))
                .isInstanceOf(RunEventCorruptionException.class)
                .hasMessageContaining("mid-log");
    }

    @Test
    void rejectsRecordMissingRequiredMetadata() throws IOException {
        String missingRun = RunEventJsonCodec.toJson(started(1))
                .replace("\"runId\":\"run-events-1\",", "");

        assertThatThrownBy(() -> RunEventJsonCodec.fromJson(missingRun))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("missing run event field: runId");
    }

    private static RunEvent.RunStarted started(long sequence) {
        return new RunEvent.RunStarted(
                metadata(sequence), List.of(UserMessage.of("start")), Optional.empty());
    }

    private static RunEventMetadata metadata(long sequence) {
        return new RunEventMetadata(
                RunEvent.CURRENT_SCHEMA_VERSION, RUN, SessionId.of("session-events-1"),
                WorkspaceId.of("workspace-events-1"), sequence,
                Instant.parse("2026-07-29T00:00:00Z").plusSeconds(sequence));
    }

    private static RunEvent.RunStopped stoppedWithPayload(
            long sequence, AiMessage finalMessage) {
        return new RunEvent.RunStopped(
                metadata(sequence), StopReason.TERMINAL_TOOL, finalMessage,
                Optional.of(Map.of("apiKey", "terminal-secret", "safe", "kept")),
                AgentUsage.zero(), BudgetConsumption.zero(), Optional.empty());
    }

    private static RunEvent.RunStopped stoppedWithSafePayload(
            long sequence, AiMessage finalMessage) {
        return new RunEvent.RunStopped(
                metadata(sequence), StopReason.TERMINAL_TOOL, finalMessage,
                Optional.of(Map.of("answer", "kept")),
                new AgentUsage(3, 2, 1),
                new BudgetConsumption(1, 1, 3, 2, 8), Optional.of("detail"));
    }

    private static RunEvent roundTrip(RunEvent event) {
        try {
            return RunEventJsonCodec.fromJson(RunEventJsonCodec.toJson(event));
        } catch (IOException failure) {
            throw new AssertionError(failure);
        }
    }

    private static String firstLine(String value) {
        int newline = value.indexOf('\n');
        return value.substring(0, newline + 1);
    }

    private static String secondLine(String value) {
        int first = value.indexOf('\n');
        int second = value.indexOf('\n', first + 1);
        return value.substring(first + 1, second + 1);
    }
}
