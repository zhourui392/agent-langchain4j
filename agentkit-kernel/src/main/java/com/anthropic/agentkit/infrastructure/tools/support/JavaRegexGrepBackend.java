package com.anthropic.agentkit.infrastructure.tools.support;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Stream;

public final class JavaRegexGrepBackend implements GrepBackend {

    @Override
    public GrepResult search(GrepRequest request, Path cwd) {
        Pattern pattern;
        try {
            pattern = Pattern.compile(request.pattern());
        } catch (PatternSyntaxException ex) {
            return GrepResult.error("invalid regex: " + ex.getMessage());
        }

        PathMatcher filter = request.globFilter() == null || request.globFilter().isEmpty()
                ? null
                : FileSystems.getDefault().getPathMatcher("glob:" + request.globFilter());

        StringBuilder out = new StringBuilder();
        try (Stream<Path> stream = Files.walk(cwd)) {
            stream.filter(Files::isRegularFile)
                    .filter(p -> !GitIgnoreFilter.shouldIgnore(p, cwd))
                    .filter(p -> filter == null || filter.matches(cwd.relativize(p)))
                    .forEach(p -> scanFile(p, cwd, pattern, request.contextLines(), out));
        } catch (IOException ex) {
            return GrepResult.error("walk error: " + ex.getMessage());
        }

        return GrepResult.ok(out.toString().stripTrailing());
    }

    private static void scanFile(Path file, Path cwd, Pattern pattern, int contextLines, StringBuilder out) {
        try {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            List<Integer> matchIndices = new ArrayList<>();
            for (int i = 0; i < lines.size(); i++) {
                if (pattern.matcher(lines.get(i)).find()) {
                    matchIndices.add(i);
                }
            }
            if (matchIndices.isEmpty()) {
                return;
            }
            String label = cwd.relativize(file).toString();
            for (int idx : matchIndices) {
                renderHit(lines, idx, contextLines, label, out);
            }
        } catch (IOException ignored) {
        }
    }

    private static void renderHit(List<String> lines, int idx, int context, String label, StringBuilder out) {
        int from = Math.max(0, idx - context);
        int to = Math.min(lines.size() - 1, idx + context);
        for (int i = from; i <= to; i++) {
            char marker = i == idx ? ':' : '-';
            out.append(label).append(marker).append(i + 1)
                    .append(marker).append(lines.get(i)).append('\n');
        }
    }
}
