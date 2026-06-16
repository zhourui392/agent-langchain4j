package com.anthropic.agentkit.infrastructure.context;

import com.anthropic.agentkit.domain.context.ContextProvider;

import java.nio.file.Path;
import java.util.Optional;

public final class CwdProvider implements ContextProvider {

    @Override
    public String key() {
        return "cwd";
    }

    @Override
    public Optional<String> provide(Path workingDirectory) {
        return Optional.of(workingDirectory.toAbsolutePath().normalize().toString());
    }

    @Override
    public boolean isDynamic() {
        return true;
    }
}
