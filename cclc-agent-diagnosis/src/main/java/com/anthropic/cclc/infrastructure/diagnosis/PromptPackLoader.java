package com.anthropic.cclc.infrastructure.diagnosis;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * Loads diagnosis prompt pack markdown files from a directory.
 *
 * @author zhourui(V33215020)
 * @since 2026-06-11
 */
public final class PromptPackLoader {

    public String load(Path directory) {
        Objects.requireNonNull(directory, "directory");
        if (!Files.isDirectory(directory)) {
            throw new IllegalArgumentException("prompt pack path is not a directory: " + directory);
        }
        try (Stream<Path> files = Files.list(directory)) {
            return render(files
                    .filter(this::isMarkdown)
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .toList());
        } catch (IOException ex) {
            throw new IllegalStateException("failed to load prompt pack: " + directory, ex);
        }
    }

    private String render(Iterable<Path> files) throws IOException {
        StringBuilder prompt = new StringBuilder();
        for (Path file : files) {
            if (!prompt.isEmpty()) {
                prompt.append("\n\n");
            }
            prompt.append("## ").append(file.getFileName()).append('\n');
            prompt.append(Files.readString(file));
        }
        return prompt.toString();
    }

    private boolean isMarkdown(Path path) {
        return Files.isRegularFile(path) && path.getFileName().toString().endsWith(".md");
    }
}
