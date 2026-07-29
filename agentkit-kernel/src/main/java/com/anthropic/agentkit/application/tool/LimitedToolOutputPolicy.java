package com.anthropic.agentkit.application.tool;

import com.anthropic.agentkit.domain.tool.ExecutionContext;
import com.anthropic.agentkit.domain.tool.ToolInvocation;
import com.anthropic.agentkit.domain.tool.ToolResult;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Character-bounded tool output policy with explicit artifact omission metadata. */
public final class LimitedToolOutputPolicy implements ToolOutputPolicy {

    public static final int DEFAULT_MAX_CHARACTERS = 32_000;
    public static final String DISPOSITION_KEY = "agentkit.output.disposition";
    public static final String ORIGINAL_CHARACTERS_KEY = "agentkit.output.original_characters";
    public static final String RETAINED_CHARACTERS_KEY = "agentkit.output.retained_characters";
    public static final String ARTIFACT_KEY = "agentkit.output.artifact";
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
            return withMetadata(raw, "complete", raw.content().length(),
                    raw.content().length(), null);
        }
        int original = raw.content().length();
        String retained = raw.content().substring(0, maxCharacters) + OMISSION_NOTICE;
        return withMetadata(raw.withContent(retained), "truncated", original,
                maxCharacters, "omitted");
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
}
