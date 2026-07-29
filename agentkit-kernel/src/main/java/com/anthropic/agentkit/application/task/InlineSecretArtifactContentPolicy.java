package com.anthropic.agentkit.application.task;

import com.anthropic.agentkit.domain.tool.ExecutionContext;

import java.util.Objects;
import java.util.regex.Pattern;

/** Default bounded-scope redaction used before output enters artifact storage. */
final class InlineSecretArtifactContentPolicy implements ArtifactContentPolicy {

    static final ArtifactContentPolicy INSTANCE = new InlineSecretArtifactContentPolicy();

    private static final Pattern INLINE_SECRET = Pattern.compile(
            "(?i)(\\b(?:api[_-]?key|access[_-]?token|refresh[_-]?token|token|password|secret|"
                    + "authorization|credential)\\b\\s*[:=]\\s*)(?:bearer\\s+)?([^\\s,;&]+)");

    private InlineSecretArtifactContentPolicy() { }

    @Override
    public String govern(String content, ExecutionContext context) {
        Objects.requireNonNull(context, "context");
        return INLINE_SECRET.matcher(
                Objects.requireNonNull(content, "content")).replaceAll("$1[REDACTED]");
    }
}
