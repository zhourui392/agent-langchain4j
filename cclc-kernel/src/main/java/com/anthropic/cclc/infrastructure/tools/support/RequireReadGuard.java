package com.anthropic.cclc.infrastructure.tools.support;

import com.anthropic.cclc.domain.tool.ToolResult;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

public final class RequireReadGuard {

    private final FileStateCache fileStateCache;

    public RequireReadGuard(FileStateCache fileStateCache) {
        this.fileStateCache = Objects.requireNonNull(fileStateCache, "fileStateCache");
    }

    public Optional<ToolResult> checkBeforeOverwrite(Path file) {
        if (!Files.exists(file)) {
            return Optional.empty();
        }
        if (!fileStateCache.hasBeenRead(file)) {
            return Optional.of(ToolResult.error(
                    "must Read " + file + " before modifying it"));
        }
        if (fileStateCache.isStale(file)) {
            return Optional.of(ToolResult.error(
                    "file modified externally since last Read: " + file
                            + " — Read it again before modifying"));
        }
        return Optional.empty();
    }
}
