package com.anthropic.agentkit.application.tool;

import com.anthropic.agentkit.domain.tool.ExecutionContext;
import com.anthropic.agentkit.domain.tool.ToolInvocation;
import com.anthropic.agentkit.domain.tool.ToolOutputMetadata;
import com.anthropic.agentkit.domain.tool.ToolResult;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Character-bounded tool output policy with explicit artifact omission metadata. */
public final class LimitedToolOutputPolicy implements ToolOutputPolicy {

    public static final int DEFAULT_MAX_CHARACTERS = 32_000;
    public static final String DISPOSITION_KEY = ToolOutputMetadata.DISPOSITION_KEY;
    public static final String ORIGINAL_CHARACTERS_KEY = ToolOutputMetadata.ORIGINAL_CHARACTERS_KEY;
    public static final String RETAINED_CHARACTERS_KEY = ToolOutputMetadata.RETAINED_CHARACTERS_KEY;
    public static final String ARTIFACT_KEY = ToolOutputMetadata.ARTIFACT_KEY;
    private static final String OMISSION_NOTICE =
            "\n\n[agentkit: remaining tool output omitted; artifact unavailable]";

    private final int maxCharacters;

    private LimitedToolOutputPolicy(int maxCharacters) {
        if (maxCharacters <= 0) {
            throw new IllegalArgumentException("maxCharacters must be positive");
        }
        this.maxCharacters = maxCharacters;
    }

    public static LimitedToolOutputPolicy defaults() {
        return of(DEFAULT_MAX_CHARACTERS);
    }

    public static LimitedToolOutputPolicy of(int maxCharacters) {
        return new LimitedToolOutputPolicy(maxCharacters);
    }

    @Override
    public ToolResult govern(
            ToolInvocation invocation, ToolResult raw, ExecutionContext context) {
        Objects.requireNonNull(invocation, "invocation");
        Objects.requireNonNull(raw, "raw");
        Objects.requireNonNull(context, "context");
        if (raw.content().length() <= maxCharacters) {
            return markCompleteUnlessAlreadyGoverned(raw);
        }
        int original = originalCharacters(raw);
        String retained = raw.content().substring(0, maxCharacters) + OMISSION_NOTICE;
        return withMetadata(raw.withContent(retained), ToolOutputMetadata.TRUNCATED,
                original, maxCharacters, artifactDisposition(raw));
    }

    private ToolResult markCompleteUnlessAlreadyGoverned(ToolResult result) {
        String disposition = result.metadata().get(DISPOSITION_KEY);
        if (disposition != null) {
            return result;
        }
        return withMetadata(result, ToolOutputMetadata.COMPLETE,
                result.content().length(), result.content().length(), null);
    }

    private ToolResult withMetadata(
            ToolResult result, String disposition, int original,
            int retained, String artifact) {
        Map<String, String> metadata = new LinkedHashMap<>(result.metadata());
        metadata.put(DISPOSITION_KEY, disposition);
        metadata.put(ORIGINAL_CHARACTERS_KEY, String.valueOf(original));
        metadata.put(RETAINED_CHARACTERS_KEY, String.valueOf(retained));
        if (artifact != null) {
            metadata.put(ARTIFACT_KEY, artifact);
        }
        return ToolResult.of(result.status(), result.content(), metadata);
    }

    private int originalCharacters(ToolResult result) {
        String recorded = result.metadata().get(ORIGINAL_CHARACTERS_KEY);
        if (recorded == null) {
            return result.content().length();
        }
        try {
            return Math.max(result.content().length(), Integer.parseInt(recorded));
        } catch (NumberFormatException ignored) {
            return result.content().length();
        }
    }

    private String artifactDisposition(ToolResult result) {
        return result.metadata().getOrDefault(ARTIFACT_KEY, ToolOutputMetadata.OMITTED);
    }
}
