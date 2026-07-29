package com.anthropic.agentkit.domain.checkpoint;

import com.anthropic.agentkit.domain.tool.ExecutionContext;

import java.nio.file.Path;
import java.util.Objects;

/** Explicit owner and filesystem root used while capturing a file snapshot. */
public record FileCheckpointScope(CheckpointOwner owner, Path workspaceRoot) {

    public FileCheckpointScope {
        Objects.requireNonNull(owner, "owner");
        workspaceRoot = Objects.requireNonNull(workspaceRoot, "workspaceRoot")
                .toAbsolutePath().normalize();
    }

    public static FileCheckpointScope from(ExecutionContext context) {
        Objects.requireNonNull(context, "context");
        return new FileCheckpointScope(
                new CheckpointOwner(context.sessionId(), context.workspaceId()),
                context.cwd());
    }
}
