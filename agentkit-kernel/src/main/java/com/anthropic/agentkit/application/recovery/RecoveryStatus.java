package com.anthropic.agentkit.application.recovery;

/** Safe recovery classification for a persisted tool invocation. */
public enum RecoveryStatus {
    SETTLED,
    UNKNOWN,
    NOT_STARTED
}
