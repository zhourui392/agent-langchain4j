package com.anthropic.agentkit.infrastructure.tools;

import com.anthropic.agentkit.domain.tool.ExecutionContext;
import com.anthropic.agentkit.domain.tool.Tool;
import com.anthropic.agentkit.domain.tool.ToolArguments;
import com.anthropic.agentkit.domain.tool.ToolResult;
import com.anthropic.agentkit.infrastructure.tools.support.GrepBackend;
import com.anthropic.agentkit.infrastructure.tools.support.JavaRegexGrepBackend;
import com.anthropic.agentkit.infrastructure.tools.support.RipgrepBackend;
import com.anthropic.agentkit.infrastructure.tools.support.WorkspaceBoundary;
import com.anthropic.agentkit.infrastructure.tools.support.WorkspaceBoundaryViolationException;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class GrepTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(GrepTool.class);
    private static final String INPUT_SCHEMA = """
            {"type":"object","properties":{\
            "pattern":{"type":"string","description":"regex"},\
            "glob":{"type":"string","description":"file glob filter"},\
            "context":{"type":"integer","description":"lines of context around each match"}\
            },"required":["pattern"]}""";

    private final GrepBackend backend;
    private final WorkspaceBoundary boundary;

    public GrepTool() {
        this(autoDetect(), new WorkspaceBoundary());
    }

    public GrepTool(GrepBackend backend) {
        this(backend, new WorkspaceBoundary());
    }

    public GrepTool(GrepBackend backend, WorkspaceBoundary boundary) {
        this.backend = Objects.requireNonNull(backend, "backend");
        this.boundary = Objects.requireNonNull(boundary, "boundary");
    }

    public static GrepBackend autoDetect() {
        return RipgrepBackend.isAvailable() ? new RipgrepBackend() : new JavaRegexGrepBackend();
    }

    @Override public String name() { return "Grep"; }
    @Override public String description() { return "Search file contents for a regex pattern"; }
    @Override public String inputSchema() { return INPUT_SCHEMA; }
    @Override public boolean isReadOnly() { return true; }

    @Override
    public ToolResult execute(ToolArguments args, ExecutionContext ctx) {
        long startNs = System.nanoTime();
        GrepBackend.GrepRequest request = new GrepBackend.GrepRequest(
                args.getString("pattern"),
                args.getString("glob", ""),
                args.getInt("context", 0));
        if (backend instanceof JavaRegexGrepBackend) {
            log.warn("grep using Java regex backend: pattern={}, glob={}",
                    request.pattern(), request.globFilter());
        }
        log.debug("grep args: pattern={}, glob={}, context={}",
                request.pattern(), request.globFilter(), request.contextLines());
        try {
            Path root = boundary.resolveExisting(ctx.cwd(), ".");
            GrepBackend.GrepResult result = backend.search(request, root);
            int hits = countHits(result.output());
            log.info("grep completed: pattern={}, backend={}, success={}, hits={}, durationMs={}",
                    request.pattern(), backendName(), result.success(), hits, elapsedMs(startNs));
            return result.success()
                    ? ToolResult.ok(result.output().isEmpty() ? "(no matches)" : result.output())
                    : ToolResult.error(result.error());
        } catch (WorkspaceBoundaryViolationException ex) {
            log.warn("grep blocked: pattern={}, reason=workspace_boundary", request.pattern());
            return ToolResult.error(ex.getMessage());
        } catch (IOException ex) {
            log.error("grep failed: pattern={}, cwd={}", request.pattern(), ctx.cwd(), ex);
            return ToolResult.error("grep error: " + ex.getMessage());
        }
    }

    private String backendName() {
        return backend instanceof RipgrepBackend ? "ripgrep" : "JavaRegex";
    }

    private static int countHits(String output) {
        if (output == null || output.isBlank()) {
            return 0;
        }
        return output.split("\\R").length;
    }

    private static long elapsedMs(long startNs) {
        return (System.nanoTime() - startNs) / 1_000_000L;
    }
}
