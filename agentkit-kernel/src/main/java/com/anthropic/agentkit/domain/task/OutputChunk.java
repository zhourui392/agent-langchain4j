package com.anthropic.agentkit.domain.task;

import java.util.Objects;

/** Stable output slice and the cursor to use for the next incremental read. */
public record OutputChunk(String content, OutputCursor next, TaskState state) {

    public OutputChunk {
        Objects.requireNonNull(content, "content");
        Objects.requireNonNull(next, "next");
        Objects.requireNonNull(state, "state");
    }
}
