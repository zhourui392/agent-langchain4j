package com.anthropic.agentkit.infrastructure.tools;

import com.anthropic.agentkit.domain.tool.ExecutionContext;
import com.anthropic.agentkit.domain.tool.Tool;
import com.anthropic.agentkit.domain.tool.ToolArguments;
import com.anthropic.agentkit.domain.tool.ToolResult;
import com.anthropic.agentkit.infrastructure.tools.support.GitIgnoreFilter;
import com.anthropic.agentkit.infrastructure.tools.support.WorkspaceBoundary;
import com.anthropic.agentkit.infrastructure.tools.support.WorkspaceBoundaryViolationException;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class GlobTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(GlobTool.class);
    private static final String INPUT_SCHEMA = """
            {"type":"object","properties":{\
            "pattern":{"type":"string","description":"glob pattern, e.g. **/*.java"},\
            "respectGitignore":{"type":"boolean","default":true}\
            },"required":["pattern"]}""";

    private final WorkspaceBoundary boundary;

    public GlobTool() {
        this(new WorkspaceBoundary());
    }

    public GlobTool(WorkspaceBoundary boundary) {
        this.boundary = Objects.requireNonNull(boundary, "boundary");
    }

    @Override public String name() { return "Glob"; }
    @Override public String description() { return "List files matching a glob pattern"; }
    @Override public String inputSchema() { return INPUT_SCHEMA; }
    @Override public boolean isReadOnly() { return true; }

    @Override
    public ToolResult execute(ToolArguments args, ExecutionContext ctx) {
        long startNs = System.nanoTime();
        String pattern = args.getString("pattern");
        boolean respectGitignore = args.getBoolean("respectGitignore", true);
        log.debug("glob args: pattern={}, respectGitignore={}", pattern, respectGitignore);
        List<PathMatcher> matchers = buildMatchers(pattern);

        try {
            Path root = boundary.resolveExisting(ctx.cwd(), ".");
            List<Path> matches = collectMatches(root, matchers, respectGitignore);
            matches.sort(Comparator.comparing(GlobTool::mtime).reversed());
            log.info("glob completed: pattern={}, matches={}, durationMs={}",
                    pattern, matches.size(), elapsedMs(startNs));
            return ToolResult.ok(formatMatches(matches, root));
        } catch (WorkspaceBoundaryViolationException ex) {
            log.warn("glob blocked: pattern={}, reason=workspace_boundary", pattern);
            return ToolResult.error(ex.getMessage());
        } catch (IOException ex) {
            log.error("glob failed: pattern={}, cwd={}", pattern, ctx.cwd(), ex);
            return ToolResult.error("glob error: " + ex.getMessage());
        }
    }

    private static List<PathMatcher> buildMatchers(String pattern) {
        List<PathMatcher> matchers = new ArrayList<>();
        matchers.add(FileSystems.getDefault().getPathMatcher("glob:" + pattern));
        if (pattern.startsWith("**/")) {
            matchers.add(FileSystems.getDefault().getPathMatcher("glob:" + pattern.substring(3)));
        }
        return matchers;
    }

    private List<Path> collectMatches(Path root, List<PathMatcher> matchers, boolean respectGitignore)
            throws IOException {
        List<Path> matches = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(root)) {
            stream.filter(p -> boundary.containsExisting(root, p))
                    .filter(Files::isRegularFile)
                    .filter(p -> !respectGitignore || !GitIgnoreFilter.shouldIgnore(p, root))
                    .forEach(p -> {
                        Path relative = root.relativize(p);
                        if (anyMatches(matchers, relative)) {
                            matches.add(p);
                        }
                    });
        }
        return matches;
    }

    private static boolean anyMatches(List<PathMatcher> matchers, Path path) {
        for (PathMatcher m : matchers) {
            if (m.matches(path)) {
                return true;
            }
        }
        return false;
    }

    private static FileTime mtime(Path file) {
        try {
            return Files.getLastModifiedTime(file);
        } catch (IOException ex) {
            return FileTime.fromMillis(0);
        }
    }

    private static String formatMatches(List<Path> matches, Path root) {
        if (matches.isEmpty()) {
            return "(no matches)";
        }
        StringBuilder sb = new StringBuilder();
        for (Path p : matches) {
            sb.append(root.relativize(p)).append('\n');
        }
        return sb.toString().stripTrailing();
    }

    private static long elapsedMs(long startNs) {
        return (System.nanoTime() - startNs) / 1_000_000L;
    }
}
