package com.anthropic.agentkit.application.tool;

import com.anthropic.agentkit.application.task.ArtifactContentPolicy;
import com.anthropic.agentkit.domain.port.ArtifactStore;
import com.anthropic.agentkit.domain.task.ArtifactReference;
import com.anthropic.agentkit.domain.task.TaskScope;
import com.anthropic.agentkit.domain.tool.ExecutionContext;
import com.anthropic.agentkit.domain.tool.ToolInvocation;
import com.anthropic.agentkit.domain.tool.ToolOutputMetadata;
import com.anthropic.agentkit.domain.tool.ToolResult;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Persists governed full output before returning a bounded preview and stable reference. */
public final class ArtifactToolOutputPolicy implements ToolOutputPolicy {

    private final int maxCharacters;
    private final ArtifactStore artifacts;
    private final ArtifactContentPolicy contentPolicy;
    private final LimitedToolOutputPolicy fallback;

    private ArtifactToolOutputPolicy(
            int maxCharacters, ArtifactStore artifacts,
            ArtifactContentPolicy contentPolicy) {
        if (maxCharacters < 1) {
            throw new IllegalArgumentException("maxCharacters must be positive");
        }
        this.maxCharacters = maxCharacters;
        this.artifacts = Objects.requireNonNull(artifacts, "artifacts");
        this.contentPolicy = Objects.requireNonNull(contentPolicy, "contentPolicy");
        this.fallback = LimitedToolOutputPolicy.of(maxCharacters);
    }

    public static ArtifactToolOutputPolicy of(
            int maxCharacters, ArtifactStore artifacts,
            ArtifactContentPolicy contentPolicy) {
        return new ArtifactToolOutputPolicy(maxCharacters, artifacts, contentPolicy);
    }

    @Override
    public ToolResult govern(
            ToolInvocation invocation, ToolResult raw, ExecutionContext context) {
        Objects.requireNonNull(invocation, "invocation");
        Objects.requireNonNull(raw, "raw");
        Objects.requireNonNull(context, "context");
        if (raw.content().length() <= maxCharacters) {
            return fallback.govern(invocation, raw, context);
        }
        String governed = contentPolicy.govern(raw.content(), context);
        Optional<ArtifactReference> reference = store(governed, context);
        return truncated(raw, governed, reference);
    }

    private Optional<ArtifactReference> store(
            String content, ExecutionContext context) {
        try {
            return Optional.of(artifacts.write(TaskScope.from(context), content));
        } catch (RuntimeException failure) {
            return Optional.empty();
        }
    }

    private ToolResult truncated(
            ToolResult raw, String governed,
            Optional<ArtifactReference> reference) {
        int retainedCharacters = Math.min(maxCharacters, governed.length());
        String retained = governed.substring(0, retainedCharacters) + notice(reference);
        Map<String, String> metadata = new LinkedHashMap<>(raw.metadata());
        metadata.put(ToolOutputMetadata.DISPOSITION_KEY, ToolOutputMetadata.TRUNCATED);
        metadata.put(ToolOutputMetadata.ORIGINAL_CHARACTERS_KEY,
                String.valueOf(raw.content().length()));
        metadata.put(ToolOutputMetadata.RETAINED_CHARACTERS_KEY,
                String.valueOf(retainedCharacters));
        metadata.put(ToolOutputMetadata.ARTIFACT_KEY, reference
                .map(value -> value.uri().toString()).orElse(ToolOutputMetadata.OMITTED));
        return ToolResult.of(raw.status(), retained, metadata);
    }

    private static String notice(Optional<ArtifactReference> reference) {
        return reference
                .map(value -> "\n\n[agentkit: full tool output stored as " + value.uri() + "]")
                .orElse("\n\n[agentkit: remaining tool output omitted; artifact unavailable]");
    }
}
