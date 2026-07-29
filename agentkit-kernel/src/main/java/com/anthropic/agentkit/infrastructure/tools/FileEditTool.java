package com.anthropic.agentkit.infrastructure.tools;

import com.anthropic.agentkit.domain.tool.ExecutionContext;
import com.anthropic.agentkit.domain.tool.Tool;
import com.anthropic.agentkit.domain.tool.ToolArguments;
import com.anthropic.agentkit.domain.tool.ToolResult;
import com.anthropic.agentkit.domain.tool.ToolSafety;
import com.anthropic.agentkit.domain.checkpoint.CheckpointId;
import com.anthropic.agentkit.domain.checkpoint.FileCheckpointException;
import com.anthropic.agentkit.domain.checkpoint.FileCheckpointMetadata;
import com.anthropic.agentkit.domain.checkpoint.FileCheckpointScope;
import com.anthropic.agentkit.domain.port.FileCheckpointProvider;
import com.anthropic.agentkit.infrastructure.tools.support.DiffRenderer;
import com.anthropic.agentkit.infrastructure.tools.support.FileStateCache;
import com.anthropic.agentkit.infrastructure.tools.support.RequireReadGuard;
import com.anthropic.agentkit.infrastructure.tools.support.StringReplacement;
import com.anthropic.agentkit.infrastructure.tools.support.WorkspaceBoundary;
import com.anthropic.agentkit.infrastructure.tools.support.WorkspaceBoundaryViolationException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import java.util.LinkedHashMap;
import java.util.Map;

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
    private final WorkspaceBoundary boundary;
    private final FileCheckpointProvider checkpoints;

    public FileEditTool(FileStateCache fileStateCache) {
        this(fileStateCache, new WorkspaceBoundary());
    }

    public FileEditTool(FileStateCache fileStateCache, WorkspaceBoundary boundary) {
        this(fileStateCache, boundary, FileCheckpointProvider.none());
    }

    public FileEditTool(
            FileStateCache fileStateCache, WorkspaceBoundary boundary,
            FileCheckpointProvider checkpoints) {
        this.fileStateCache = Objects.requireNonNull(fileStateCache, "fileStateCache");
        this.requireReadGuard = new RequireReadGuard(fileStateCache);
        this.boundary = Objects.requireNonNull(boundary, "boundary");
        this.checkpoints = Objects.requireNonNull(checkpoints, "checkpoints");
    }

    @Override public String name() { return "Edit"; }
    @Override public String description() { return "Replace an exact string in a file"; }
    @Override public String inputSchema() { return INPUT_SCHEMA; }
    @Override public boolean isReadOnly() { return false; }
    @Override public ToolSafety safety() { return ToolSafety.checkpointedFileMutation(); }

    @Override
    public ToolResult execute(ToolArguments args, ExecutionContext ctx) {
        long startNs = System.nanoTime();
        String requested = args.getString("path");
        StringReplacement replacement = new StringReplacement(
                args.getString("old_string"),
                args.getString("new_string"),
                args.getBoolean("replace_all", false));
        log.debug("file edit args: path={}, oldChars={}, newChars={}, replaceAll={}",
                requested, args.getString("old_string").length(),
                args.getString("new_string").length(), replacement.replaceAll());

        try {
            Path file = boundary.resolveExisting(ctx.cwd(), requested);
            Optional<ToolResult> guard = requireReadGuard.checkBeforeOverwrite(ctx, file);
            if (guard.isPresent()) {
                log.warn("file edit blocked: path={}", file);
                return guard.get();
            }
            String original = Files.readString(file, StandardCharsets.UTF_8);
            return applyEdit(ctx, file, original, replacement, startNs);
        } catch (NoSuchFileException ex) {
            log.warn("file edit failed: path={}, reason=not_found", requested);
            return ToolResult.error("file not found: " + requested);
        } catch (WorkspaceBoundaryViolationException ex) {
            log.warn("file edit blocked: path={}, reason=workspace_boundary", requested);
            return ToolResult.error(ex.getMessage());
        } catch (FileCheckpointException ex) {
            log.error("file checkpoint failed: path={}", requested, ex);
            return ToolResult.error("checkpoint error: " + ex.getMessage());
        } catch (IOException ex) {
            log.error("file edit failed: path={}", requested, ex);
            return ToolResult.error("edit error: " + ex.getMessage());
        }
    }

    private ToolResult applyEdit(ExecutionContext context, Path file, String original,
                                 StringReplacement replacement, long startNs)
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
        Optional<CheckpointId> checkpoint = checkpoints.capture(
                FileCheckpointScope.from(context), file);
        Files.writeString(file, updated, StandardCharsets.UTF_8);
        fileStateCache.recordRead(context, file);
        String diff = DiffRenderer.unifiedDiff(original, updated, file.toString());
        log.info("file edit completed: path={}, replacements={}, durationMs={}",
                file, occurrences, elapsedMs(startNs));
        return checkpointed(
                ToolResult.ok("edited " + file + "\n" + diff), checkpoint);
    }

    private static ToolResult checkpointed(
            ToolResult result, Optional<CheckpointId> checkpoint) {
        if (checkpoint.isEmpty()) {
            return result;
        }
        Map<String, String> metadata = new LinkedHashMap<>(result.metadata());
        metadata.put(FileCheckpointMetadata.CHECKPOINT_ID_KEY,
                checkpoint.orElseThrow().value());
        return ToolResult.of(result.status(), result.content(), metadata);
    }

    private static long elapsedMs(long startNs) {
        return (System.nanoTime() - startNs) / 1_000_000L;
    }
}
