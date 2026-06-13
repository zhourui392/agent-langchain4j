package com.anthropic.cclc.infrastructure.context;

import com.anthropic.cclc.domain.context.ContextProvider;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

public final class AgentsMdProvider implements ContextProvider {

    private static final String FILENAME = "AGENTS.md";

    @Override
    public String key() {
        return "agents_md";
    }

    @Override
    public Optional<String> provide(Path workingDirectory) {
        List<String> snippets = new ArrayList<>();
        for (Path dir : ancestorsFromRoot(workingDirectory)) {
            Path file = dir.resolve(FILENAME);
            readIfExists(file).ifPresent(content ->
                    snippets.add("# " + file + "\n" + content.stripTrailing()));
        }
        if (snippets.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(String.join("\n\n", snippets));
    }

    private static List<Path> ancestorsFromRoot(Path cwd) {
        List<Path> chain = new ArrayList<>();
        Path current = cwd.toAbsolutePath().normalize();
        while (current != null) {
            chain.add(current);
            current = current.getParent();
        }
        Collections.reverse(chain);
        return chain;
    }

    private static Optional<String> readIfExists(Path file) {
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try {
            return Optional.of(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException ex) {
            return Optional.empty();
        }
    }
}
