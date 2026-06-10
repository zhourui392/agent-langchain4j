package com.anthropic.cclc.infrastructure.tools;

import com.anthropic.cclc.domain.tool.ExecutionContext;
import com.anthropic.cclc.domain.tool.Tool;
import com.anthropic.cclc.domain.tool.ToolArguments;
import com.anthropic.cclc.domain.tool.ToolResult;
import com.anthropic.cclc.infrastructure.tools.support.FileStateCache;
import com.anthropic.cclc.infrastructure.tools.support.RequireReadGuard;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

public final class FileWriteTool implements Tool {

    private static final String INPUT_SCHEMA = """
            {"type":"object","properties":{\
            "path":{"type":"string","description":"absolute or cwd-relative file path"},\
            "content":{"type":"string","description":"file contents"}\
            },"required":["path","content"]}""";

    private final FileStateCache fileStateCache;
    private final RequireReadGuard requireReadGuard;

    public FileWriteTool(FileStateCache fileStateCache) {
        this.fileStateCache = Objects.requireNonNull(fileStateCache, "fileStateCache");
        this.requireReadGuard = new RequireReadGuard(fileStateCache);
    }

    @Override public String name() { return "Write"; }
    @Override public String description() { return "Create or overwrite a UTF-8 text file"; }
    @Override public String inputSchema() { return INPUT_SCHEMA; }
    @Override public boolean isReadOnly() { return false; }

    @Override
    public ToolResult execute(ToolArguments args, ExecutionContext ctx) {
        Path file = ctx.cwd().resolve(args.getString("path")).normalize();
        String content = args.getString("content");

        Optional<ToolResult> guard = requireReadGuard.checkBeforeOverwrite(file);
        if (guard.isPresent()) {
            return guard.get();
        }

        try {
            if (file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }
            Files.writeString(file, content, StandardCharsets.UTF_8);
            fileStateCache.recordRead(file);
            return ToolResult.ok("wrote " + file);
        } catch (IOException ex) {
            return ToolResult.error("write error: " + ex.getMessage());
        }
    }
}
