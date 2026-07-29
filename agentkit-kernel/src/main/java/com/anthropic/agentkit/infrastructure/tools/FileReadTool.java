package com.anthropic.agentkit.infrastructure.tools;

import com.anthropic.agentkit.domain.tool.ExecutionContext;
import com.anthropic.agentkit.domain.tool.Tool;
import com.anthropic.agentkit.domain.tool.ToolArguments;
import com.anthropic.agentkit.domain.tool.ToolResult;
import com.anthropic.agentkit.infrastructure.tools.support.FileStateCache;
import com.anthropic.agentkit.infrastructure.tools.support.FileTextLoader;

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

    public FileReadTool(FileStateCache fileStateCache) {
        this.fileStateCache = Objects.requireNonNull(fileStateCache, "fileStateCache");
    }

    @Override public String name() { return "Read"; }
    @Override public String description() { return "Read a UTF-8 text file"; }
    @Override public String inputSchema() { return INPUT_SCHEMA; }
    @Override public boolean isReadOnly() { return true; }

    @Override
    public ToolResult execute(ToolArguments args, ExecutionContext ctx) {
        long startNs = System.nanoTime();
        Path file = ctx.cwd().resolve(args.getString("path")).normalize();
        int maxLines = args.getInt("maxLines", DEFAULT_MAX_LINES);
        log.debug("file read args: path={}, maxLines={}", file, maxLines);

        try {
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
            log.warn("file read failed: path={}, reason=not_found", file);
            return ToolResult.error("file not found: " + file);
        } catch (IOException ex) {
            log.error("file read failed: path={}", file, ex);
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
