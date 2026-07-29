package com.anthropic.agentkit.infrastructure.memory;

import com.anthropic.agentkit.domain.agent.RunId;
import com.anthropic.agentkit.domain.port.RunEventPersistenceException;
import com.anthropic.agentkit.domain.port.RunEventStore;
import com.anthropic.agentkit.domain.run.RunEvent;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.Base64;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** File-backed append-only JSONL store with per-run monotonic sequence validation. */
public final class FileRunEventStore implements RunEventStore {

    private static final Set<PosixFilePermission> OWNER_ONLY = EnumSet.of(
            PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);

    private final Path baseDirectory;

    public FileRunEventStore(Path baseDirectory) {
        this.baseDirectory = Objects.requireNonNull(baseDirectory, "baseDirectory");
    }

    @Override
    public synchronized void append(RunEvent event) {
        Objects.requireNonNull(event, "event");
        Path file = pathFor(event.metadata().runId());
        List<RunEvent> existing = load(event.metadata().runId());
        validateNext(existing, event);
        try {
            Files.createDirectories(baseDirectory);
            appendLine(file, RunEventJsonCodec.toJson(event) + "\n");
            restrictPermissions(file);
        } catch (IOException failure) {
            throw new RunEventPersistenceException(
                    "failed to append run event " + event.metadata().runId(), failure);
        }
    }

    @Override
    public synchronized List<RunEvent> load(RunId runId) {
        Objects.requireNonNull(runId, "runId");
        Path file = pathFor(runId);
        if (!Files.exists(file)) {
            return List.of();
        }
        try {
            List<RunEvent> events = decode(Files.readString(file, StandardCharsets.UTF_8));
            validateStream(runId, events);
            return List.copyOf(events);
        } catch (RunEventCorruptionException failure) {
            throw failure;
        } catch (IOException failure) {
            throw new RunEventPersistenceException("failed to load run events " + runId, failure);
        }
    }

    public Path pathFor(RunId runId) {
        String encoded = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(runId.value().getBytes(StandardCharsets.UTF_8));
        return baseDirectory.resolve(encoded + ".events.jsonl");
    }

    private List<RunEvent> decode(String content) {
        boolean terminated = content.endsWith("\n");
        String[] lines = content.split("\n", -1);
        int lastRecord = terminated ? lines.length - 2 : lines.length - 1;
        List<RunEvent> events = new ArrayList<>(Math.max(0, lastRecord + 1));
        for (int index = 0; index <= lastRecord; index++) {
            if (lines[index].isBlank()) {
                throw corruption(index, null);
            }
            try {
                events.add(RunEventJsonCodec.fromJson(lines[index]));
            } catch (IOException | RuntimeException failure) {
                if (index == lastRecord && !terminated) {
                    break;
                }
                throw corruption(index, failure);
            }
        }
        return events;
    }

    private void validateNext(List<RunEvent> existing, RunEvent event) {
        long expected = existing.isEmpty()
                ? 1 : existing.getLast().metadata().sequence() + 1;
        if (event.metadata().sequence() != expected) {
            throw new IllegalArgumentException(
                    "expected sequence " + expected + " for run "
                            + event.metadata().runId() + " but got "
                            + event.metadata().sequence());
        }
    }

    private void validateStream(RunId runId, List<RunEvent> events) {
        long expected = 1;
        for (RunEvent event : events) {
            if (!event.metadata().runId().equals(runId)) {
                throw new RunEventCorruptionException("run id changed within event log");
            }
            if (event.metadata().sequence() != expected++) {
                throw new RunEventCorruptionException("event sequence is not monotonic per run");
            }
        }
    }

    private void appendLine(Path file, String line) throws IOException {
        byte[] bytes = line.getBytes(StandardCharsets.UTF_8);
        try (FileChannel channel = FileChannel.open(file,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) {
                channel.write(buffer);
            }
            channel.force(true);
        }
    }

    private void restrictPermissions(Path file) {
        try {
            Files.setPosixFilePermissions(file, OWNER_ONLY);
        } catch (UnsupportedOperationException | IOException ignored) {
            // Non-POSIX platforms rely on their native user profile ACLs.
        }
    }

    private RunEventCorruptionException corruption(int index, Throwable failure) {
        String message = "mid-log run event corruption at record " + (index + 1);
        return failure == null
                ? new RunEventCorruptionException(message)
                : new RunEventCorruptionException(message, failure);
    }
}
