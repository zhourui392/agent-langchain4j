package com.anthropic.agentkit.domain.agent;

import java.nio.file.Path;
import java.util.Objects;

/** Stable logical identity of a workspace, independent from a run. */
public record WorkspaceId(String value) {

    public WorkspaceId {
        Objects.requireNonNull(value, "value");
        if (value.isBlank()) {
            throw new IllegalArgumentException("workspace id must not be blank");
        }
    }

    public static WorkspaceId of(String value) {
        return new WorkspaceId(value);
    }

    public static WorkspaceId fromPath(Path workspaceRoot) {
        Objects.requireNonNull(workspaceRoot, "workspaceRoot");
        return new WorkspaceId(workspaceRoot.toAbsolutePath().normalize().toString());
    }

    @Override
    public String toString() {
        return value;
    }
}
