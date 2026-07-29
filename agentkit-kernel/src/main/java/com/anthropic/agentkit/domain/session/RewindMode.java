package com.anthropic.agentkit.domain.session;

/** Resources the caller explicitly asks a rewind to compensate. */
public enum RewindMode {
    CONVERSATION_ONLY,
    CONVERSATION_AND_FILES
}
