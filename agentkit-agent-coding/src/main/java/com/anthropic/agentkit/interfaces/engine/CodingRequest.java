package com.anthropic.agentkit.interfaces.engine;

import com.anthropic.agentkit.domain.agent.AgentRunContext;

import java.util.Objects;

/** One coding task and its explicit run scope. */
public record CodingRequest(
        String taskId,
        String requirement,
        AgentRunContext context) {

    public CodingRequest {
        taskId = requireText(taskId, "taskId");
        requirement = requireText(requirement, "requirement");
        Objects.requireNonNull(context, "context");
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
