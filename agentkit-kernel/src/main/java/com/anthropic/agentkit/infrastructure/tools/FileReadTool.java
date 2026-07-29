package com.anthropic.agentkit.infrastructure.tools;

import com.anthropic.agentkit.domain.tool.ExecutionContext;
import com.anthropic.agentkit.domain.tool.Tool;
import com.anthropic.agentkit.domain.tool.ToolArguments;
import com.anthropic.agentkit.domain.tool.ToolResult;
import com.anthropic.agentkit.infrastructure.tools.support.FileStateCache;
import com.anthropic.agentkit.infrastructure.tools.support.FileTextLoader;
import com.anthropic.agentkit.infrastructure.tools.support.WorkspaceBoundary;
import com.anthropic.agentkit.infrastructure.tools.support.WorkspaceBoundaryViolationException;

import java.io.IOException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class FileReadTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(FileReadTool.class);
    private static final int DEFAULT_MAX_LINES = 2000;
    private static final String INPUT_SCHEMA = """
            {"type":"object","properties":{\
            "path":{"type":"string","description":"absolute or cwd-relative file path"},\
            "maxLines":{"type":"integer","description":"max lines to read (default 2000)"}\
            },"required":["path"]}""";

    private final FileStateCache fileStateCache;
    private final WorkspaceBoundary boundary;

    public FileReadTool(FileStateCache fileStateCache) {
        this(fileStateCache, new WorkspaceBoundary());
    }

    public FileReadTool(FileStateCache fileStateCache, WorkspaceBoundary boundary) {
        this.fileStateCache = Objects.requireNonNull(fileStateCache, "fileStateCache");
        this.boundary = Objects.requireNonNull(boundary, "boundary");
    }

    @Override public String name() { return "Read"; }
    @Override public String description() { return "Read a UTF-8 text file"; }
    @Override public String inputSchema() { return INPUT_SCHEMA; }
    @Override public boolean isReadOnly() { return true; }

    @Override
    public ToolResult execute(ToolArguments args, ExecutionContext ctx) {
        long startNs = System.nanoTime();
        String requested = args.getString("path");
        int maxLines = args.getInt("maxLines", DEFAULT_MAX_LINES);
        log.debug("file read args: path={}, maxLines={}", requested, maxLines);

        try {
            Path file = boundary.resolveExisting(ctx.cwd(), requested);
            FileTextLoader.LoadResult loaded = new FileTextLoader(maxLines).load(file);
            fileStateCache.recordRead(ctx, file);
            if (loaded.truncated()) {
                log.warn("file read truncated: path={}, totalLines={}, maxLines={}",
                        file, loaded.totalLines(), maxLines);
            }
            log.info("file read completed: path={}, lines={}, bytes={}, durationMs={}",
                    file, loaded.totalLines(), loaded.content().getBytes(java.nio.charset.StandardCharsets.UTF_8).length,
                    elapsedMs(startNs));
            return ToolResult.ok(renderOutput(loaded));
        } catch (NoSuchFileException ex) {
            log.warn("file read failed: path={}, reason=not_found", requested);
            return ToolResult.error("file not found: " + requested);
        } catch (WorkspaceBoundaryViolationException ex) {
            log.warn("file read blocked: path={}, reason=workspace_boundary", requested);
            return ToolResult.error(ex.getMessage());
        } catch (IOException ex) {
            log.error("file read failed: path={}", requested, ex);
            return ToolResult.error("read error: " + ex.getMessage());
        }
    }

    private static String renderOutput(FileTextLoader.LoadResult loaded) {
        if (!loaded.truncated()) {
            return loaded.content();
        }
        return loaded.content() + "\n... [truncated; " + loaded.totalLines() + " lines total]";
    }

    private static long elapsedMs(long startNs) {
        return (System.nanoTime() - startNs) / 1_000_000L;
    }
}
