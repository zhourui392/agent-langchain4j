package com.anthropic.cclc.infrastructure.tools.support;

import java.nio.file.Path;
import java.util.Set;

public final class GitIgnoreFilter {

    private static final Set<String> ALWAYS_IGNORED = Set.of(
            ".git", "node_modules", "target", "build", ".gradle",
            ".idea", ".vscode", "dist", "out", ".next", "__pycache__");

    private GitIgnoreFilter() {
    }

    public static boolean shouldIgnore(Path file, Path root) {
        Path relative = root.relativize(file);
        for (Path segment : relative) {
            if (ALWAYS_IGNORED.contains(segment.toString())) {
                return true;
            }
        }
        return false;
    }
}
