package com.anthropic.agentkit.infrastructure.memory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

final class JsonlAppender {

    private JsonlAppender() {
    }

    static void writeAtomically(Path target, List<String> lines) throws IOException {
        if (target.getParent() != null) {
            Files.createDirectories(target.getParent());
        }
        Path temp = Files.createTempFile(target.getParent(), "jsonl-", ".tmp");
        try {
            StringBuilder content = new StringBuilder();
            for (String line : lines) {
                content.append(line).append('\n');
            }
            Files.writeString(temp, content.toString(), StandardCharsets.UTF_8);
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception ex) {
            try { Files.deleteIfExists(temp); } catch (IOException ignored) {}
            if (ex instanceof IOException io) {
                throw io;
            }
            throw new IOException(ex);
        }
    }

    static List<String> readLines(Path source) throws IOException {
        if (!Files.exists(source)) {
            return List.of();
        }
        return Files.readAllLines(source, StandardCharsets.UTF_8).stream()
                .filter(line -> !line.isBlank())
                .toList();
    }
}
