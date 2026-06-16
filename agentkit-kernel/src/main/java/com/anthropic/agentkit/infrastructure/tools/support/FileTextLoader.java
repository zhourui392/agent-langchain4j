package com.anthropic.agentkit.infrastructure.tools.support;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public final class FileTextLoader {

    private final int maxLines;

    public FileTextLoader(int maxLines) {
        if (maxLines <= 0) {
            throw new IllegalArgumentException("maxLines must be positive");
        }
        this.maxLines = maxLines;
    }

    public LoadResult load(Path file) throws IOException {
        try (Stream<String> stream = Files.lines(file, StandardCharsets.UTF_8)) {
            long total = countLines(file);
            String content = stream.limit(maxLines).collect(Collectors.joining("\n"));
            boolean truncated = total > maxLines;
            return new LoadResult(content, total, truncated);
        }
    }

    private static long countLines(Path file) throws IOException {
        try (Stream<String> s = Files.lines(file, StandardCharsets.UTF_8)) {
            return s.count();
        }
    }

    public record LoadResult(String content, long totalLines, boolean truncated) {}
}
