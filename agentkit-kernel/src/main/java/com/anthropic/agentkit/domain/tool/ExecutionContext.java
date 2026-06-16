package com.anthropic.agentkit.domain.tool;

import com.anthropic.agentkit.domain.conversation.CancellationToken;

import java.nio.file.Path;
import java.util.Objects;

public record ExecutionContext(Path cwd, CancellationToken cancellation) {

    public ExecutionContext {
        Objects.requireNonNull(cwd, "cwd");
        Objects.requireNonNull(cancellation, "cancellation");
    }

    public static ExecutionContext at(Path cwd) {
        return new ExecutionContext(cwd, new CancellationToken());
    }

    public static ExecutionContext of(Path cwd, CancellationToken cancellation) {
        return new ExecutionContext(cwd, cancellation);
    }
}
