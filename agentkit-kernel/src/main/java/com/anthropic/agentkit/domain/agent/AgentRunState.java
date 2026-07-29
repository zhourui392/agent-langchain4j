package com.anthropic.agentkit.domain.agent;

/** Observable lifecycle state of a sub-agent handle. */
public enum AgentRunState {
    STARTING,
    RUNNING,
    COMPLETED,
    FAILED,
    CANCELLED
}
