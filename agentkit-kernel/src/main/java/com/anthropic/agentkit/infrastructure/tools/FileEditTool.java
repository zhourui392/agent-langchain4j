package com.anthropic.agentkit.infrastructure.tools;

import com.anthropic.agentkit.domain.tool.ExecutionContext;
import com.anthropic.agentkit.domain.tool.Tool;
import com.anthropic.agentkit.domain.tool.ToolArguments;
import com.anthropic.agentkit.domain.tool.ToolResult;
import com.anthropic.agentkit.infrastructure.tools.support.DiffRenderer;
import com.anthropic.agentkit.infrastructure.tools.support.FileStateCache;
import com.anthropic.agentkit.infrastructure.tools.support.RequireReadGuard;
import com.anthropic.agentkit.infrastructure.tools.support.StringReplacement;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class FileEditTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(FileEditTool.class);
    private static final String INPUT_SCHEMA = """
            {"type":"object","properties":{\
            "path":{"type":"string"},\
            "old_string":{"type":"string"},\
            "new_string":{"type":"string"},\
            "replace_all":{"type":"boolean","default":false}\
            },"required":["path","old_string","new_string"]}""";

    private final FileStateCache fileStateCache;
    private final RequireReadGuard requireReadGuard;

    public FileEditTool(FileStateCache fileStateCache) {
        this.fileStateCache = Objects.requireNonNull(fileStateCache, "fileStateCache");
        this.requireReadGuard = new RequireReadGuard(fileStateCache);
    }

    @Override public String name() { return "Edit"; }
    @Override public String description() { return "Replace an exact string in a file"; }
    @Override public String inputSchema() { return INPUT_SCHEMA; }
    @Override public boolean isReadOnly() { return false; }

    @Override
    public ToolResult execute(ToolArguments args, ExecutionContext ctx) {
        long startNs = System.nanoTime();
        Path file = ctx.cwd().resolve(args.getString("path")).normalize();
        StringReplacement replacement = new StringReplacement(
                args.getString("old_string"),
                args.getString("new_string"),
                args.getBoolean("replace_all", false));
        log.debug("file edit args: path={}, oldChars={}, newChars={}, replaceAll={}",
                file, args.getString("old_string").length(),
                args.getString("new_string").length(), replacement.replaceAll());

        Optional<ToolResult> guard = requireReadGuard.checkBeforeOverwrite(file);
        if (guard.isPresent()) {
            log.warn("file edit blocked: path={}", file);
            return guard.get();
        }

        try {
            String original = Files.readString(file, StandardCharsets.UTF_8);
            return applyEdit(file, original, replacement, startNs);
        } catch (NoSuchFileException ex) {
            log.warn("file edit failed: path={}, reason=not_found", file);
            return ToolResult.error("file not found: " + file);
        } catch (IOException ex) {
            log.error("file edit failed: path={}", file, ex);
            return ToolResult.error("edit error: " + ex.getMessage());
        }
    }

    private ToolResult applyEdit(Path file, String original, StringReplacement replacement, long startNs)
            throws IOException {
        int occurrences = replacement.countOccurrences(original);
        if (occurrences == 0) {
            log.warn("file edit failed: path={}, reason=old_string_not_found", file);
            return ToolResult.error("old_string not found in " + file);
        }
        if (occurrences > 1 && !replacement.replaceAll()) {
            log.warn("file edit failed: path={}, reason=ambiguous_occurrences, occurrences={}", file, occurrences);
            return ToolResult.error("old_string appears " + occurrences
                    + " times; pass replace_all=true or add more context to make it unique");
        }
        String updated = replacement.applyTo(original);
        Files.writeString(file, updated, StandardCharsets.UTF_8);
        fileStateCache.recordRead(file);
        String diff = DiffRenderer.unifiedDiff(original, updated, file.toString());
        log.info("file edit completed: path={}, replacements={}, durationMs={}",
                file, occurrences, elapsedMs(startNs));
        return ToolResult.ok("edited " + file + "\n" + diff);
    }

    private static long elapsedMs(long startNs) {
        return (System.nanoTime() - startNs) / 1_000_000L;
    }
}
