package com.anthropic.agentkit.domain.port;

/** Stable port-level failure for artifact persistence or retrieval. */
public final class ArtifactStoreException extends RuntimeException {

    public ArtifactStoreException(String message, Throwable cause) {
        super(message, cause);
    }
}
