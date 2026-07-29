package com.anthropic.agentkit.domain.tool;

import com.anthropic.agentkit.domain.checkpoint.CheckpointId;

import java.util.Objects;

/** Typed, durable observation of one already-started tool side effect. */
public sealed interface ToolSideEffect permits
        ToolSideEffect.CheckpointedFile,
        ToolSideEffect.NonReversible {

    record CheckpointedFile(CheckpointId checkpointId) implements ToolSideEffect {
        public CheckpointedFile {
            Objects.requireNonNull(checkpointId, "checkpointId");
        }
    }

    record NonReversible(String toolName, String detail) implements ToolSideEffect {
        public NonReversible {
            if (toolName == null || toolName.isBlank()) {
                throw new IllegalArgumentException("toolName must not be blank");
            }
            if (detail == null || detail.isBlank()) {
                throw new IllegalArgumentException("detail must not be blank");
            }
        }
    }
}
