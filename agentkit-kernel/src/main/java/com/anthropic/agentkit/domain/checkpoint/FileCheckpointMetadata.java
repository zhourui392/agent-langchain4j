package com.anthropic.agentkit.domain.checkpoint;

/** Stable tool-result metadata used to correlate writes with checkpoint facts. */
public final class FileCheckpointMetadata {

    public static final String CHECKPOINT_ID_KEY = "agentkit.checkpoint.id";

    private FileCheckpointMetadata() {
    }
}
