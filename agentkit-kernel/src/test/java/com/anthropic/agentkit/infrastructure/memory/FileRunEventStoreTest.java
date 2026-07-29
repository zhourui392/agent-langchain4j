package com.anthropic.agentkit.infrastructure.memory;

import com.anthropic.agentkit.domain.agent.RunId;
import com.anthropic.agentkit.domain.agent.WorkspaceId;
import com.anthropic.agentkit.domain.conversation.SessionId;
import com.anthropic.agentkit.domain.message.AiMessage;
import com.anthropic.agentkit.domain.message.UserMessage;
import com.anthropic.agentkit.domain.run.RunEvent;
import com.anthropic.agentkit.domain.run.RunEventMetadata;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
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
