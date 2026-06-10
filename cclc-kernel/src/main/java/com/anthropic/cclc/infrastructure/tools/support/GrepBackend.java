package com.anthropic.cclc.infrastructure.tools.support;

import java.nio.file.Path;

public interface GrepBackend {

    GrepResult search(GrepRequest request, Path cwd);

    record GrepRequest(String pattern, String globFilter, int contextLines) {}

    record GrepResult(boolean success, String output, String error) {
        public static GrepResult ok(String output) {
            return new GrepResult(true, output, "");
        }

        public static GrepResult error(String message) {
            return new GrepResult(false, "", message);
        }
    }
}
