package com.anthropic.agentkit.infrastructure.tools;

import com.anthropic.agentkit.domain.tool.ExecutionContext;
import com.anthropic.agentkit.domain.tool.Tool;
import com.anthropic.agentkit.domain.tool.ToolArguments;
import com.anthropic.agentkit.domain.tool.ToolResult;
import com.anthropic.agentkit.infrastructure.tools.support.FileStateCache;
import com.anthropic.agentkit.infrastructure.tools.support.RequireReadGuard;
import com.anthropic.agentkit.infrastructure.tools.support.WorkspaceBoundary;
import com.anthropic.agentkit.infrastructure.tools.support.WorkspaceBoundaryViolationException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class FileWriteTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(FileWriteTool.class);
    private static final String INPUT_SCHEMA = """
            {"type":"object","properties":{\
            "path":{"type":"string","description":"absolute or cwd-relative file path"},\
            "content":{"type":"string","description":"file contents"}\
            },"required":["path","content"]}""";

    private final FileStateCache fileStateCache;
    private final RequireReadGuard requireReadGuard;
    private final WorkspaceBoundary boundary;

    public FileWriteTool(FileStateCache fileStateCache) {
        this(fileStateCache, new WorkspaceBoundary());
    }

    public FileWriteTool(FileStateCache fileStateCache, WorkspaceBoundary boundary) {
        this.fileStateCache = Objects.requireNonNull(fileStateCache, "fileStateCache");
        this.requireReadGuard = new RequireReadGuard(fileStateCache);
        this.boundary = Objects.requireNonNull(boundary, "boundary");
    }

    @Override public String name() { return "Write"; }
    @Override public String description() { return "Create or overwrite a UTF-8 text file"; }
    @Override public String inputSchema() { return INPUT_SCHEMA; }
    @Override public boolean isReadOnly() { return false; }

    @Override
    public ToolResult execute(ToolArguments args, ExecutionContext ctx) {
        long startNs = System.nanoTime();
        String requested = args.getString("path");
        String content = args.getString("content");
        log.debug("file write args: path={}, bytes={}", requested,
                content.getBytes(StandardCharsets.UTF_8).length);

        try {
            Path file = boundary.resolveForCreate(ctx.cwd(), requested);
            boolean existedBefore = Files.exists(file);
            Optional<ToolResult> guard = requireReadGuard.checkBeforeOverwrite(ctx, file);
            if (guard.isPresent()) {
                log.warn("file write blocked: path={}", file);
                return guard.get();
            }
            if (file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }
            Files.writeString(file, content, StandardCharsets.UTF_8);
            fileStateCache.recordRead(ctx, file);
            log.info("file write completed: path={}, bytes={}, mode={}, durationMs={}",
                    file, content.getBytes(StandardCharsets.UTF_8).length,
                    existedBefore ? "overwrite" : "create", elapsedMs(startNs));
            return ToolResult.ok("wrote " + file);
        } catch (WorkspaceBoundaryViolationException ex) {
            log.warn("file write blocked: path={}, reason=workspace_boundary", requested);
            return ToolResult.error(ex.getMessage());
        } catch (IOException ex) {
            log.error("file write failed: path={}", requested, ex);
            return ToolResult.error("write error: " + ex.getMessage());
        }
    }

    private static long elapsedMs(long startNs) {
        return (System.nanoTime() - startNs) / 1_000_000L;
    }
}
