package com.anthropic.agentkit.infrastructure.tools.support;

import com.anthropic.agentkit.domain.tool.ToolResult;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class RequireReadGuard {

    private static final Logger log = LoggerFactory.getLogger(RequireReadGuard.class);

    private final FileStateCache fileStateCache;

    public RequireReadGuard(FileStateCache fileStateCache) {
        this.fileStateCache = Objects.requireNonNull(fileStateCache, "fileStateCache");
    }

    public Optional<ToolResult> checkBeforeOverwrite(Path file) {
        if (!Files.exists(file)) {
            return Optional.empty();
        }
        if (!fileStateCache.hasBeenRead(file)) {
            log.warn("read-before-write guard blocked: file={}, reason=not_read", file);
            return Optional.of(ToolResult.error(
                    "must Read " + file + " before modifying it"));
        }
        if (fileStateCache.isStale(file)) {
            log.warn("read-before-write guard blocked: file={}, reason=stale", file);
            return Optional.of(ToolResult.error(
                    "file modified externally since last Read: " + file
                            + " — Read it again before modifying"));
        }
        return Optional.empty();
    }
}
