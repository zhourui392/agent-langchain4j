package com.anthropic.agentkit.infrastructure.memory;

import com.anthropic.agentkit.domain.conversation.SessionId;
import com.anthropic.agentkit.domain.message.AiMessage;
import com.anthropic.agentkit.domain.message.ChatMessage;
import com.anthropic.agentkit.domain.message.SystemMessage;
import com.anthropic.agentkit.domain.message.ToolResultMessage;
import com.anthropic.agentkit.domain.message.UserMessage;
import com.anthropic.agentkit.domain.tool.ToolUseId;
import com.anthropic.agentkit.domain.tool.ToolUseRequest;
import com.anthropic.agentkit.domain.tool.ToolResultStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FileChatMemoryStoreTest {

    @Test
    void savesAndLoadsRoundTrip(@TempDir Path dir) {
        FileChatMemoryStore store = new FileChatMemoryStore(dir);
        SessionId id = SessionId.of("session-1");
        List<ChatMessage> messages = List.of(
                UserMessage.of("hi"),
                AiMessage.text("hello"),
                SystemMessage.of("be brief"));

        store.save(id, messages);
        List<ChatMessage> loaded = store.load(id);

        assertThat(loaded).containsExactlyElementsOf(messages);
    }

    @Test
    void appendIsAtomicAfterPartialCrash(@TempDir Path dir) throws IOException {
        FileChatMemoryStore store = new FileChatMemoryStore(dir);
        SessionId id = SessionId.of("session-1");
        store.save(id, List.of(UserMessage.of("first")));

        // simulate crash mid-append: a partial truncated line at end of file
        Path file = store.pathFor(id);
        Files.writeString(file, Files.readString(file) + "{\"corrupt\":");

        List<ChatMessage> loaded = store.load(id);

        assertThat(loaded).hasSize(1);
        assertThat(loaded.get(0)).isEqualTo(UserMessage.of("first"));
    }

    @Test
    void deleteRemovesFile(@TempDir Path dir) {
        FileChatMemoryStore store = new FileChatMemoryStore(dir);
        SessionId id = SessionId.of("session-1");
        store.save(id, List.of(UserMessage.of("hi")));

        store.delete(id);

        assertThat(Files.exists(store.pathFor(id))).isFalse();
        assertThat(store.load(id)).isEmpty();
    }

    @Test
    void preservesToolUseAndToolResultPairing(@TempDir Path dir) {
        FileChatMemoryStore store = new FileChatMemoryStore(dir);
        SessionId id = SessionId.of("session-1");
        ToolUseId useId = new ToolUseId("u1");
        List<ChatMessage> messages = List.of(
                UserMessage.of("read it"),
                AiMessage.of("calling",
                        List.of(new ToolUseRequest(useId, "Read", "{\"path\":\"a.txt\"}"))),
                ToolResultMessage.of(useId, "file content"),
                AiMessage.text("done"));

        store.save(id, messages);
        List<ChatMessage> loaded = store.load(id);

        assertThat(loaded).containsExactlyElementsOf(messages);
    }

    @Test
    void roundTripsToolResultStatusAndMetadata(@TempDir Path dir) {
        FileChatMemoryStore store = new FileChatMemoryStore(dir);
        SessionId id = SessionId.of("status-session");
        ToolResultMessage result = ToolResultMessage.of(
                new ToolUseId("u-status"), ToolResultStatus.TIMEOUT,
                "timed out", java.util.Map.of("timeoutMs", "25"));

        store.save(id, List.of(result));

        assertThat(store.load(id)).containsExactly(result);
    }

    @Test
    void oldToolResultWithoutStatusDefaultsToSuccess(@TempDir Path dir) throws IOException {
        FileChatMemoryStore store = new FileChatMemoryStore(dir);
        SessionId id = SessionId.of("legacy-session");
        Files.writeString(store.pathFor(id),
                "{\"type\":\"toolResult\",\"toolUseId\":\"old-1\",\"text\":\"legacy\"}\n");

        ToolResultMessage loaded = (ToolResultMessage) store.load(id).getFirst();

        assertThat(loaded.status()).isEqualTo(ToolResultStatus.SUCCESS);
        assertThat(loaded.metadata()).isEmpty();
    }

    @Test
    void loadingMissingSessionReturnsEmpty(@TempDir Path dir) {
        FileChatMemoryStore store = new FileChatMemoryStore(dir);
        assertThat(store.load(SessionId.of("none"))).isEmpty();
    }
}
