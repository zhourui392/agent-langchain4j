package com.anthropic.agentkit.application.task;

import com.anthropic.agentkit.domain.task.ArtifactReference;

import java.util.Optional;

/** Bounded preview and artifact-reference policy for completed background output. */
public record BackgroundTaskPolicy(int previewCharacters) {

    public static final int DEFAULT_PREVIEW_CHARACTERS = 4_000;

    public BackgroundTaskPolicy {
        if (previewCharacters < 1) {
            throw new IllegalArgumentException("previewCharacters must be positive");
        }
    }

    public static BackgroundTaskPolicy defaults() {
        return of(DEFAULT_PREVIEW_CHARACTERS);
    }

    public static BackgroundTaskPolicy of(int previewCharacters) {
        return new BackgroundTaskPolicy(previewCharacters);
    }

    String preview(String content, Optional<ArtifactReference> artifact) {
        if (content.length() <= previewCharacters) {
            return content;
        }
        String reference = artifact.map(value -> value.uri().toString()).orElse("omitted");
        return content.substring(0, previewCharacters)
                + "\n\n[agentkit: remaining task output stored as " + reference + "]";
    }
}
