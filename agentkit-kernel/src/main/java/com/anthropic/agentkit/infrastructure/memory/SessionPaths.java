package com.anthropic.agentkit.infrastructure.memory;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Objects;

public final class SessionPaths {

    private final Path baseDirectory;

    public SessionPaths(Path baseDirectory) {
        this.baseDirectory = Objects.requireNonNull(baseDirectory, "baseDirectory");
    }

    public static SessionPaths defaultLocation() {
        return new SessionPaths(Paths.get(
                System.getProperty("user.home", "."),
                ".agentkit",
                "sessions"));
    }

    public Path baseDirectory() {
        return baseDirectory;
    }
}
