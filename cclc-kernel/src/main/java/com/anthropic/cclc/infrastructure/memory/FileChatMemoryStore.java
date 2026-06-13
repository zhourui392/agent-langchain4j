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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class FileChatMemoryStore implements ChatMemoryStore {

    private static final Logger log = LoggerFactory.getLogger(FileChatMemoryStore.class);

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
                    log.warn("skipping malformed session line: sessionId={}, file={}", sessionId, file);
                }
            }
            log.info("session loaded: sessionId={}, lines={}, messages={}, file={}",
                    sessionId, lines.size(), messages.size(), file);
            return messages;
        } catch (IOException ex) {
            log.error("failed to load session: sessionId={}, file={}", sessionId, file, ex);
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
            log.info("session saved: sessionId={}, messages={}, file={}", sessionId, messages.size(), file);
        } catch (IOException ex) {
            log.error("failed to save session: sessionId={}, file={}", sessionId, file, ex);
            throw new IllegalStateException("failed to save session " + sessionId, ex);
        }
    }

    @Override
    public void delete(SessionId sessionId) {
        Path file = pathFor(sessionId);
        try {
            Files.deleteIfExists(file);
            log.info("session deleted: sessionId={}, file={}", sessionId, file);
        } catch (IOException ex) {
            log.error("failed to delete session: sessionId={}, file={}", sessionId, file, ex);
            throw new IllegalStateException("failed to delete session " + sessionId, ex);
        }
    }

    public Path pathFor(SessionId sessionId) {
        return baseDirectory.resolve(sessionId.value() + ".jsonl");
    }
}
