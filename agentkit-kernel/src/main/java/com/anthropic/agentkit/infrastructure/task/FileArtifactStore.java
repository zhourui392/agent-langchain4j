package com.anthropic.agentkit.infrastructure.task;

import com.anthropic.agentkit.domain.port.ArtifactStore;
import com.anthropic.agentkit.domain.port.ArtifactStoreException;
import com.anthropic.agentkit.domain.task.ArtifactId;
import com.anthropic.agentkit.domain.task.ArtifactLimitExceededException;
import com.anthropic.agentkit.domain.task.ArtifactReference;
import com.anthropic.agentkit.domain.task.TaskScope;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;

/** Scope-derived, size-bounded, expiring file storage for full task output. */
public final class FileArtifactStore implements ArtifactStore {

    private final Path root;
    private final int maxCharacters;
    private final Duration ttl;
    private final Clock clock;

    public FileArtifactStore(
            Path root, int maxCharacters, Duration ttl, Clock clock) {
        this.root = Objects.requireNonNull(root, "root").toAbsolutePath().normalize();
        if (maxCharacters < 1) {
            throw new IllegalArgumentException("maxCharacters must be positive");
        }
        this.maxCharacters = maxCharacters;
        this.ttl = requirePositive(ttl);
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public ArtifactReference write(TaskScope scope, String content) {
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(content, "content");
        if (content.length() > maxCharacters) {
            throw new ArtifactLimitExceededException(content.length(), maxCharacters);
        }
        ArtifactId id = ArtifactId.fresh();
        Instant expiresAt = clock.instant().plus(ttl);
        Path target = path(scope, id, expiresAt);
        try {
            Files.createDirectories(target.getParent());
            ownerOnlyDirectory(target.getParent());
            Files.writeString(target, content, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            ownerOnlyFile(target);
            return new ArtifactReference(id, URI.create("artifact://" + id.value()),
                    content.length(), expiresAt);
        } catch (Exception failure) {
            throw new ArtifactStoreException("failed to write task artifact", failure);
        }
    }

    @Override
    public Optional<String> read(TaskScope scope, ArtifactReference reference) {
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(reference, "reference");
        Path target = path(scope, reference.id(), reference.expiresAt());
        if (!reference.expiresAt().isAfter(clock.instant())) {
            deleteQuietly(target);
            return Optional.empty();
        }
        try {
            if (!Files.isRegularFile(target)) {
                return Optional.empty();
            }
            String content = Files.readString(target, StandardCharsets.UTF_8);
            if (content.length() > maxCharacters) {
                throw new ArtifactLimitExceededException(content.length(), maxCharacters);
            }
            return Optional.of(content);
        } catch (ArtifactLimitExceededException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new ArtifactStoreException("failed to read task artifact", failure);
        }
    }

    private Path path(TaskScope scope, ArtifactId id, Instant expiresAt) {
        String scopeKey = digest(scope.runId().value() + "\u0000" + scope.workspaceId().value());
        String artifactKey = digest(id.value());
        Path target = root.resolve(scopeKey)
                .resolve(artifactKey + "-" + expiresAt.toEpochMilli() + ".txt").normalize();
        if (!target.startsWith(root)) {
            throw new IllegalArgumentException("artifact path escapes configured root");
        }
        return target;
    }

    private static String digest(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(
                    value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception failure) {
            throw new IllegalStateException("SHA-256 is unavailable", failure);
        }
    }

    private static void ownerOnlyDirectory(Path target) {
        setPermissions(target, "rwx------");
    }

    private static void ownerOnlyFile(Path target) {
        setPermissions(target, "rw-------");
    }

    private static void setPermissions(Path target, String permissions) {
        try {
            Files.setPosixFilePermissions(
                    target, PosixFilePermissions.fromString(permissions));
        } catch (UnsupportedOperationException | java.io.IOException ignored) {
            // Non-POSIX platforms rely on the parent workspace/user boundary.
        }
    }

    private static void deleteQuietly(Path target) {
        try {
            Files.deleteIfExists(target);
        } catch (Exception ignored) {
            // Expiration still denies the read even when physical deletion must retry later.
        }
    }

    private static Duration requirePositive(Duration value) {
        Objects.requireNonNull(value, "ttl");
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException("ttl must be positive");
        }
        return value;
    }
}
