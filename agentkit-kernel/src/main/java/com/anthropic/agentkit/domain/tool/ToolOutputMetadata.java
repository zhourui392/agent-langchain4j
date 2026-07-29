package com.anthropic.agentkit.domain.tool;

/** Stable metadata protocol describing whether a tool result is complete or externally omitted. */
public final class ToolOutputMetadata {

    public static final String DISPOSITION_KEY = "agentkit.output.disposition";
    public static final String ORIGINAL_CHARACTERS_KEY = "agentkit.output.original_characters";
    public static final String RETAINED_CHARACTERS_KEY = "agentkit.output.retained_characters";
    public static final String ARTIFACT_KEY = "agentkit.output.artifact";

    public static final String COMPLETE = "complete";
    public static final String TRUNCATED = "truncated";
    public static final String OMITTED = "omitted";

    private ToolOutputMetadata() {
    }
}
