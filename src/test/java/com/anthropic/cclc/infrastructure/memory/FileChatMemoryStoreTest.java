package com.anthropic.cclc.infrastructure.memory;

import com.anthropic.cclc.domain.conversation.SessionId;
import com.anthropic.cclc.domain.message.AiMessage;
import com.anthropic.cclc.domain.message.ChatMessage;
import com.anthropic.cclc.domain.message.SystemMessage;
import com.anthropic.cclc.domain.message.ToolResultMessage;
import com.anthropic.cclc.domain.message.UserMessage;
import com.anthropic.cclc.domain.tool.ToolUseId;
import com.anthropic.cclc.domain.tool.ToolUseRequest;
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
    void loadingMissingSessionReturnsEmpty(@TempDir Path dir) {
        FileChatMemoryStore store = new FileChatMemoryStore(dir);
        assertThat(store.load(SessionId.of("none"))).isEmpty();
    }
}
