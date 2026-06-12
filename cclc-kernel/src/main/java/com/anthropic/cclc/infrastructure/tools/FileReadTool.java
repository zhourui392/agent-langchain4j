package com.anthropic.cclc.infrastructure.tools;

import com.anthropic.cclc.domain.tool.ExecutionContext;
import com.anthropic.cclc.domain.tool.Tool;
import com.anthropic.cclc.domain.tool.ToolArguments;
import com.anthropic.cclc.domain.tool.ToolResult;
import com.anthropic.cclc.infrastructure.tools.support.FileStateCache;
import com.anthropic.cclc.infrastructure.tools.support.FileTextLoader;

import java.io.IOException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Objects;

public final class FileReadTool implements Tool {

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
        Path file = ctx.cwd().resolve(args.getString("path")).normalize();
        int maxLines = args.getInt("maxLines", DEFAULT_MAX_LINES);

        try {
            FileTextLoader.LoadResult loaded = new FileTextLoader(maxLines).load(file);
            fileStateCache.recordRead(file);
            return ToolResult.ok(renderOutput(loaded));
        } catch (NoSuchFileException ex) {
            return ToolResult.error("file not found: " + file);
        } catch (IOException ex) {
            return ToolResult.error("read error: " + ex.getMessage());
        }
    }

    private static String renderOutput(FileTextLoader.LoadResult loaded) {
        if (!loaded.truncated()) {
            return loaded.content();
        }
        return loaded.content() + "\n... [truncated; " + loaded.totalLines() + " lines total]";
    }
}
