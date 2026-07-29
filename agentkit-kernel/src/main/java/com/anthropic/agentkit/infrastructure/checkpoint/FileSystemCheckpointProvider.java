package com.anthropic.agentkit.infrastructure.checkpoint;

import com.anthropic.agentkit.domain.checkpoint.CheckpointId;
import com.anthropic.agentkit.domain.checkpoint.CheckpointOwner;
import com.anthropic.agentkit.domain.checkpoint.FileCheckpointException;
import com.anthropic.agentkit.domain.checkpoint.FileCheckpointScope;
import com.anthropic.agentkit.domain.port.FileCheckpointProvider;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Base64;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Owner-scoped durable snapshots for kernel-managed file writes. */
public final class FileSystemCheckpointProvider implements FileCheckpointProvider {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<PosixFilePermission> OWNER_DIRECTORY = EnumSet.of(
            PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.OWNER_EXECUTE);
    private static final Set<PosixFilePermission> OWNER_FILE = EnumSet.of(
            PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);

    private final Path baseDirectory;

    public FileSystemCheckpointProvider(Path baseDirectory) {
        this.baseDirectory = Objects.requireNonNull(baseDirectory, "baseDirectory")
                .toAbsolutePath().normalize();
    }

    @Override
    public Optional<CheckpointId> capture(FileCheckpointScope scope, Path file) {
        Objects.requireNonNull(scope, "scope");
        Path normalized = requireInside(scope.workspaceRoot(), file);
        try {
            Snapshot snapshot = snapshot(scope, normalized);
            write(snapshot);
            return Optional.of(CheckpointId.of(snapshot.checkpointId()));
        } catch (IOException failure) {
            throw new FileCheckpointException("failed to capture file checkpoint", failure);
        }
    }

    @Override
    public void restore(CheckpointOwner owner, CheckpointId checkpointId) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(checkpointId, "checkpointId");
        try {
            Snapshot snapshot = read(checkpointId);
            requireOwner(snapshot, owner);
            restore(snapshot);
        } catch (FileCheckpointException failure) {
            throw failure;
        } catch (IOException failure) {
            throw new FileCheckpointException("failed to restore file checkpoint", failure);
        }
    }

    public Path pathFor(CheckpointId checkpointId) {
        return baseDirectory.resolve(checkpointId.value() + ".checkpoint.json");
    }

    private Snapshot snapshot(FileCheckpointScope scope, Path file) throws IOException {
        boolean existed = Files.exists(file);
        if (existed && !Files.isRegularFile(file)) {
            throw new FileCheckpointException("only regular files can be checkpointed");
        }
        byte[] content = existed ? Files.readAllBytes(file) : new byte[0];
        long modified = existed ? Files.getLastModifiedTime(file).toMillis() : -1L;
        CheckpointId id = CheckpointId.fresh();
        Path relative = scope.workspaceRoot().relativize(file);
        return new Snapshot(
                1, id.value(), scope.owner().sessionId().value(),
                scope.owner().workspaceId().value(), scope.workspaceRoot().toString(),
                relative.toString(), existed, modified,
                Base64.getEncoder().encodeToString(content));
    }

    private void write(Snapshot snapshot) throws IOException {
        Files.createDirectories(baseDirectory);
        restrict(baseDirectory, OWNER_DIRECTORY);
        Path target = pathFor(CheckpointId.of(snapshot.checkpointId()));
        Path temporary = baseDirectory.resolve(snapshot.checkpointId() + ".tmp");
        byte[] content = JSON.writeValueAsBytes(snapshot);
        writeAndSync(temporary, content);
        moveAtomically(temporary, target);
        restrict(target, OWNER_FILE);
    }

    private Snapshot read(CheckpointId id) throws IOException {
        Path path = pathFor(id);
        if (!Files.isRegularFile(path)) {
            throw new FileCheckpointException("file checkpoint is unavailable");
        }
        Snapshot snapshot = JSON.readValue(path.toFile(), Snapshot.class);
        if (snapshot.schemaVersion() != 1 || !snapshot.checkpointId().equals(id.value())) {
            throw new FileCheckpointException("file checkpoint is invalid");
        }
        return snapshot;
    }

    private void restore(Snapshot snapshot) throws IOException {
        Path root = Path.of(snapshot.workspaceRoot()).toAbsolutePath().normalize();
        Path file = requireInside(root, root.resolve(snapshot.relativePath()));
        if (!snapshot.existed()) {
            Files.deleteIfExists(file);
            return;
        }
        if (file.getParent() != null) {
            Files.createDirectories(file.getParent());
        }
        Files.write(file, Base64.getDecoder().decode(snapshot.contentBase64()));
        if (snapshot.modifiedMillis() >= 0) {
            Files.setLastModifiedTime(file, FileTime.fromMillis(snapshot.modifiedMillis()));
        }
    }

    private static void requireOwner(Snapshot snapshot, CheckpointOwner owner) {
        if (!snapshot.sessionId().equals(owner.sessionId().value())
                || !snapshot.workspaceId().equals(owner.workspaceId().value())) {
            throw new FileCheckpointException("file checkpoint is unavailable");
        }
    }

    private static Path requireInside(Path workspaceRoot, Path file) {
        Path root = workspaceRoot.toAbsolutePath().normalize();
        Path normalized = Objects.requireNonNull(file, "file").toAbsolutePath().normalize();
        if (!normalized.startsWith(root)) {
            throw new FileCheckpointException("checkpoint path escapes workspace root");
        }
        return normalized;
    }

    private static void writeAndSync(Path file, byte[] content) throws IOException {
        try (FileChannel channel = FileChannel.open(file,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE)) {
            ByteBuffer buffer = ByteBuffer.wrap(content);
            while (buffer.hasRemaining()) {
                channel.write(buffer);
            }
            channel.force(true);
        }
    }

    private static void moveAtomically(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException unsupported) {
            Files.move(source, target);
        }
    }

    private static void restrict(Path path, Set<PosixFilePermission> permissions) {
        try {
            Files.setPosixFilePermissions(path, permissions);
        } catch (UnsupportedOperationException | IOException ignored) {
            // Non-POSIX systems rely on their native user profile ACLs.
        }
    }

    private record Snapshot(
            int schemaVersion,
            String checkpointId,
            String sessionId,
            String workspaceId,
            String workspaceRoot,
            String relativePath,
            boolean existed,
            long modifiedMillis,
            String contentBase64) {
    }
}
