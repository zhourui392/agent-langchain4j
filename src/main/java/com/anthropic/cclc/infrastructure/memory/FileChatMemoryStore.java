package com.anthropic.cclc.infrastructure.memory;

import com.anthropic.cclc.domain.conversation.SessionId;
import com.anthropic.cclc.domain.message.ChatMessage;
import com.anthropic.cclc.domain.port.ChatMemoryStore;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class FileChatMemoryStore implements ChatMemoryStore {

    private final Path baseDirectory;

    public FileChatMemoryStore(Path baseDirectory) {
        this.baseDirectory = Objects.requireNonNull(baseDirectory, "baseDirectory");
    }

    @Override
    public List<ChatMessage> load(SessionId sessionId) {
        Path file = pathFor(sessionId);
        try {
            List<String> lines = JsonlAppender.readLines(file);
            List<ChatMessage> messages = new ArrayList<>(lines.size());
            for (String line : lines) {
                try {
                    messages.add(MessageJsonCodec.fromJson(line));
                } catch (IOException partialWrite) {
                    // skip malformed line — most likely a crash mid-append
                }
            }
            return messages;
        } catch (IOException ex) {
            throw new IllegalStateException("failed to load session " + sessionId, ex);
        }
    }

    @Override
    public void save(SessionId sessionId, List<ChatMessage> messages) {
        Path file = pathFor(sessionId);
        try {
            List<String> lines = new ArrayList<>(messages.size());
            for (ChatMessage message : messages) {
                lines.add(MessageJsonCodec.toJson(message));
            }
            JsonlAppender.writeAtomically(file, lines);
        } catch (IOException ex) {
            throw new IllegalStateException("failed to save session " + sessionId, ex);
        }
    }

    @Override
    public void delete(SessionId sessionId) {
        Path file = pathFor(sessionId);
        try {
            Files.deleteIfExists(file);
        } catch (IOException ex) {
            throw new IllegalStateException("failed to delete session " + sessionId, ex);
        }
    }

    public Path pathFor(SessionId sessionId) {
        return baseDirectory.resolve(sessionId.value() + ".jsonl");
    }
}
