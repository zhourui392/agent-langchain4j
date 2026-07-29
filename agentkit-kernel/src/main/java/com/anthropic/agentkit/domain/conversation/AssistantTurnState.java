package com.anthropic.agentkit.domain.conversation;

/** Lifecycle of the ordered tool batch emitted by one assistant turn. */
public enum AssistantTurnState {
    RECEIVED,
    SETTLING,
    SETTLED
}
