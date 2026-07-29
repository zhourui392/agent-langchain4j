package com.anthropic.agentkit.application.agent;

/** Raised before invocation when the selected agent lacks required configuration. */
public final class AgentConfigurationException extends IllegalStateException {

    public AgentConfigurationException(String message) {
        super(message);
    }
}
