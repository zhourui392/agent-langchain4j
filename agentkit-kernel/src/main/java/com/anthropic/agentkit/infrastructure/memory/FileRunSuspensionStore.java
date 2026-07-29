package com.anthropic.agentkit.infrastructure.memory;

import com.anthropic.agentkit.domain.port.RunSuspensionStore;
import com.anthropic.agentkit.domain.port.RunSuspensionStoreException;
import com.anthropic.agentkit.domain.port.RunSuspensionUnavailableException;
import com.anthropic.agentkit.domain.suspension.ResumeScope;
import com.anthropic.agentkit.domain.suspension.ResumeToken;
import com.anthropic.agentkit.domain.suspension.RunSuspension;
import com.anthropic.agentkit.domain.suspension.SuspensionKind;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Owner-only file store whose atomic pending-to-claimed move consumes a token once. */
public final class FileRunSuspensionStore implements RunSuspensionStore {

    private static final Set<PosixFilePermission> OWNER_DIRECTORY = EnumSet.of(
            PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.OWNER_EXECUTE);
    private static final Set<PosixFilePermission> OWNER_FILE = EnumSet.of(
            PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);

    private final Path directory;

    public FileRunSuspensionStore(Path directory) {
        this.directory = Objects.requireNonNull(directory, "directory")
                .toAbsolutePath().normalize();
    }

    @Override
    public void save(RunSuspension suspension, ResumeToken token) {
        Objects.requireNonNull(suspension, "suspension");
        Objects.requireNonNull(token, "token");
        Path pending = pendingPath(token);
        Path claimed = claimedPath(token);
        Path temporary = directory.resolve("." + UUID.randomUUID() + ".tmp");
        try {
            prepareDirectory();
            if (Files.exists(pending) || Files.exists(claimed)) {
                throw new IOException("resume token digest collision");
            }
            Files.writeString(temporary, RunSuspensionJsonCodec.toJson(suspension),
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE);
            restrict(temporary, OWNER_FILE);
            moveAtomically(temporary, pending);
        } catch (IOException failure) {
            deleteQuietly(temporary);
            throw new RunSuspensionStoreException(
                    "failed to persist run suspension", failure);
        }
    }

    @Override
    public RunSuspension claim(
            ResumeToken token, ResumeScope scope, SuspensionKind expectedKind) {
        Objects.requireNonNull(token, "token");
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(expectedKind, "expectedKind");
        Path pending = pendingPath(token);
        try {
            RunSuspension suspension = RunSuspensionJsonCodec.fromJson(
                    Files.readString(pending, StandardCharsets.UTF_8));
            validateClaim(suspension, scope, expectedKind);
            moveAtomically(pending, claimedPath(token));
            return suspension;
        } catch (NoSuchFileException failure) {
            throw new RunSuspensionUnavailableException(failure);
        } catch (RunSuspensionUnavailableException failure) {
            throw failure;
        } catch (IOException | RuntimeException failure) {
            if (!Files.exists(pending)) {
                throw new RunSuspensionUnavailableException(failure);
            }
            throw new RunSuspensionStoreException(
                    "failed to claim run suspension", failure);
        }
    }

    private static void validateClaim(
            RunSuspension suspension, ResumeScope resume,
            SuspensionKind expectedKind) {
        boolean matches = suspension.kind() == expectedKind
                && suspension.scope().sessionId().equals(resume.sessionId())
                && suspension.scope().workspaceId().equals(resume.workspaceId())
                && !suspension.scope().originatingRunId().equals(resume.runId());
        if (!matches) {
            throw new RunSuspensionUnavailableException();
        }
    }

    private void prepareDirectory() throws IOException {
        Files.createDirectories(directory);
        restrict(directory, OWNER_DIRECTORY);
    }

    private Path pendingPath(ResumeToken token) {
        return directory.resolve(digest(token) + ".pending.json");
    }

    private Path claimedPath(ResumeToken token) {
        return directory.resolve(digest(token) + ".claimed.json");
    }

    private static String digest(ResumeToken token) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(token.value().getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    private static void moveAtomically(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException unsupported) {
            Files.move(source, target);
        }
    }

    private static void restrict(
            Path path, Set<PosixFilePermission> permissions) {
        try {
            Files.setPosixFilePermissions(path, permissions);
        } catch (UnsupportedOperationException | IOException ignored) {
            // Non-POSIX platforms rely on their native user profile ACLs.
        }
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // Original persistence failure remains authoritative.
        }
    }
}
