package com.anthropic.agentkit.infrastructure.tools.support;

import java.nio.file.Path;
import java.time.Duration;
import java.time.ZoneId;
import java.util.Objects;
import java.util.Set;

/**
 * Host-owned allowlist and hard limits for one local logical log source.
 *
 * @author alex
 */
public record LocalLogSource(String id, Path root, Set<String> allowedGlobs, ZoneId logZone,
                             int maxFiles, int maxLines, long maxBytes, int maxDepth,
                             Duration maxScanDuration) {

    private static final int DEFAULT_MAX_DEPTH = 16;
    private static final Duration DEFAULT_MAX_SCAN_DURATION = Duration.ofSeconds(2);

    public LocalLogSource(String id, Path root, Set<String> allowedGlobs, ZoneId logZone,
                          int maxFiles, int maxLines, long maxBytes) {
        this(id, root, allowedGlobs, logZone, maxFiles, maxLines, maxBytes,
                DEFAULT_MAX_DEPTH, DEFAULT_MAX_SCAN_DURATION);
    }

    public LocalLogSource {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("id must not be blank");
        }
        Path configuredRoot = Objects.requireNonNull(root, "root");
        if (!configuredRoot.isAbsolute()) {
            throw new IllegalArgumentException("root must be absolute");
        }
        root = configuredRoot.normalize();
        allowedGlobs = Set.copyOf(Objects.requireNonNull(allowedGlobs, "allowedGlobs"));
        if (allowedGlobs.isEmpty() || allowedGlobs.stream().anyMatch(LocalLogSource::unsafeGlob)) {
            throw new IllegalArgumentException("allowed glob must be relative and bounded");
        }
        logZone = Objects.requireNonNull(logZone, "logZone");
        if (maxFiles <= 0 || maxLines <= 0 || maxBytes <= 0 || maxDepth <= 0) {
            throw new IllegalArgumentException("local log limits must be positive");
        }
        maxScanDuration = Objects.requireNonNull(maxScanDuration, "maxScanDuration");
        if (maxScanDuration.isZero() || maxScanDuration.isNegative()) {
            throw new IllegalArgumentException("maxScanDuration must be positive");
        }
    }

    private static boolean unsafeGlob(String glob) {
        return glob == null || glob.isBlank() || glob.contains("..")
                || glob.startsWith("/") || glob.startsWith("\\");
    }
}
