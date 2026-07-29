package com.anthropic.agentkit.domain.task;

/** Zero-based append-only character position in background output. */
public record OutputCursor(long position) {

    public static final OutputCursor START = new OutputCursor(0);

    public OutputCursor {
        if (position < 0) {
            throw new IllegalArgumentException("output cursor must not be negative");
        }
    }
}
