package com.anthropic.agentkit.application.task;

import com.anthropic.agentkit.domain.tool.ExecutionContext;

import java.util.Objects;

/** Governs full task output before it reaches durable artifact storage. */
@FunctionalInterface
public interface ArtifactContentPolicy {

    String govern(String content, ExecutionContext context);

    static ArtifactContentPolicy identity() {
        return (content, context) -> Objects.requireNonNull(content, "content");
    }

    static ArtifactContentPolicy redactInlineSecrets() {
        return InlineSecretArtifactContentPolicy.INSTANCE;
    }
}
