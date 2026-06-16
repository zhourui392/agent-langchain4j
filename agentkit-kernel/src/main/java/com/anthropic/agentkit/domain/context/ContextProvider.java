package com.anthropic.agentkit.domain.context;

import java.nio.file.Path;
import java.util.Optional;

public interface ContextProvider {

    String key();

    Optional<String> provide(Path workingDirectory);

    default boolean isDynamic() {
        return false;
    }
}
