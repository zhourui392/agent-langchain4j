package com.anthropic.cclc.infrastructure.tools;

import com.anthropic.cclc.domain.tool.ExecutionContext;
import com.anthropic.cclc.domain.tool.Tool;
import com.anthropic.cclc.domain.tool.ToolArguments;
import com.anthropic.cclc.domain.tool.ToolResult;
import com.anthropic.cclc.infrastructure.tools.support.GitIgnoreFilter;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

public final class GlobTool implements Tool {

    private static final String INPUT_SCHEMA = """
            {"type":"object","properties":{\
            "pattern":{"type":"string","description":"glob pattern, e.g. **/*.java"},\
            "respectGitignore":{"type":"boolean","default":true}\
            },"required":["pattern"]}""";

    @Override public String name() { return "Glob"; }
    @Override public String description() { return "List files matching a glob pattern"; }
    @Override public String inputSchema() { return INPUT_SCHEMA; }
    @Override public boolean isReadOnly() { return true; }

    @Override
    public ToolResult execute(ToolArguments args, ExecutionContext ctx) {
        String pattern = args.getString("pattern");
        boolean respectGitignore = args.getBoolean("respectGitignore", true);
        List<PathMatcher> matchers = buildMatchers(pattern);

        try {
            List<Path> matches = collectMatches(ctx.cwd(), matchers, respectGitignore);
            matches.sort(Comparator.comparing(GlobTool::mtime).reversed());
            return ToolResult.ok(formatMatches(matches, ctx.cwd()));
        } catch (IOException ex) {
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

    private static List<Path> collectMatches(Path root, List<PathMatcher> matchers, boolean respectGitignore)
            throws IOException {
        List<Path> matches = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(root)) {
            stream.filter(Files::isRegularFile)
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
}
