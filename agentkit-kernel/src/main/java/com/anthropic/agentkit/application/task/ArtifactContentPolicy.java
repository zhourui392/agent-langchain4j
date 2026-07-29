package com.anthropic.agentkit.application.task;

import com.anthropic.agentkit.domain.tool.ExecutionContext;

import java.util.Objects;
import java.util.regex.Pattern;

/** Governs full task output before it reaches durable artifact storage. */
@FunctionalInterface
public interface ArtifactContentPolicy {

    Pattern INLINE_SECRET = Pattern.compile(
            "(?i)(\\b(?:api[_-]?key|access[_-]?token|refresh[_-]?token|token|password|secret|"
                    + "authorization|credential)\\b\\s*[:=]\\s*)(?:bearer\\s+)?([^\\s,;&]+)");

    String govern(String content, ExecutionContext context);

    static ArtifactContentPolicy identity() {
        return (content, context) -> Objects.requireNonNull(content, "content");
    }

    static ArtifactContentPolicy redactInlineSecrets() {
        return (content, context) -> INLINE_SECRET.matcher(
                Objects.requireNonNull(content, "content")).replaceAll("$1[REDACTED]");
    }
}
