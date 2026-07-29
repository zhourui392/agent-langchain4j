package com.anthropic.agentkit.infrastructure.memory;

import com.anthropic.agentkit.domain.port.SessionBranchStore;
import com.anthropic.agentkit.domain.session.SessionBranchEvent;
import com.anthropic.agentkit.domain.session.SessionBranchId;
import com.anthropic.agentkit.domain.session.SessionBranchPersistenceException;

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

/** Owner-local append-only JSONL journal for immutable session branches. */
public final class FileSessionBranchStore implements SessionBranchStore {

    private static final Set<PosixFilePermission> OWNER_ONLY = EnumSet.of(
            PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
    private static final Set<PosixFilePermission> OWNER_DIRECTORY = EnumSet.of(
            PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.OWNER_EXECUTE);

    private final Path baseDirectory;

    public FileSessionBranchStore(Path baseDirectory) {
        this.baseDirectory = Objects.requireNonNull(baseDirectory, "baseDirectory");
    }

    @Override
    public synchronized void append(SessionBranchEvent event) {
        Objects.requireNonNull(event, "event");
        SessionBranchId branchId = event.branch().id();
        List<SessionBranchEvent> existing = load(branchId);
        if (!existing.isEmpty()) {
            throw new IllegalArgumentException("session branch creation is immutable");
        }
        long expected = existing.size() + 1L;
        if (event.sequence() != expected) {
            throw new IllegalArgumentException(
                    "expected branch sequence " + expected + " but got " + event.sequence());
        }
        try {
            Files.createDirectories(baseDirectory);
            restrictPermissions(baseDirectory, OWNER_DIRECTORY);
            appendLine(pathFor(branchId), SessionBranchJsonCodec.toJson(event) + "\n");
            restrictPermissions(pathFor(branchId), OWNER_ONLY);
        } catch (IOException failure) {
            throw persistence("append", branchId, failure);
        }
    }

    @Override
    public synchronized List<SessionBranchEvent> load(SessionBranchId branchId) {
        Objects.requireNonNull(branchId, "branchId");
        Path file = pathFor(branchId);
        if (!Files.exists(file)) {
            return List.of();
        }
        try {
            List<SessionBranchEvent> events = decode(Files.readString(file));
            validate(branchId, events);
            return List.copyOf(events);
        } catch (IOException | RuntimeException failure) {
            if (failure instanceof SessionBranchPersistenceException persistence) {
                throw persistence;
            }
            throw persistence("load", branchId, failure);
        }
    }

    public Path pathFor(SessionBranchId branchId) {
        String encoded = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(branchId.value().getBytes(StandardCharsets.UTF_8));
        return baseDirectory.resolve(encoded + ".branch.jsonl");
    }

    private static List<SessionBranchEvent> decode(String content) throws IOException {
        List<SessionBranchEvent> events = new ArrayList<>();
        String[] lines = content.split("\n", -1);
        int records = content.endsWith("\n") ? lines.length - 1 : lines.length;
        for (int index = 0; index < records; index++) {
            if (lines[index].isBlank()) {
                throw new IOException("blank session branch record");
            }
            events.add(SessionBranchJsonCodec.fromJson(lines[index]));
        }
        return events;
    }

    private static void validate(
            SessionBranchId branchId, List<SessionBranchEvent> events) {
        long expected = 1;
        for (SessionBranchEvent event : events) {
            if (!event.branch().id().equals(branchId)) {
                throw new IllegalArgumentException("branch id changed within journal");
            }
            if (event.sequence() != expected++) {
                throw new IllegalArgumentException("branch sequence is not contiguous");
            }
        }
    }

    private static void appendLine(Path file, String line) throws IOException {
        byte[] bytes = line.getBytes(StandardCharsets.UTF_8);
        try (FileChannel channel = FileChannel.open(file,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                StandardOpenOption.APPEND)) {
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) {
                channel.write(buffer);
            }
            channel.force(true);
        }
    }

    private static void restrictPermissions(
            Path file, Set<PosixFilePermission> permissions) {
        try {
            Files.setPosixFilePermissions(file, permissions);
        } catch (UnsupportedOperationException | IOException ignored) {
            // Non-POSIX systems rely on their native user profile ACLs.
        }
    }

    private static SessionBranchPersistenceException persistence(
            String action, SessionBranchId id, Throwable failure) {
        return new SessionBranchPersistenceException(
                "failed to " + action + " session branch " + id, failure);
    }
}
