package com.anthropic.agentkit.domain.task;

import java.net.URI;
import java.time.Instant;
import java.util.Objects;

/** Non-file reference to scoped, expiring artifact content. */
public record ArtifactReference(
        ArtifactId id, URI uri, long characters, Instant expiresAt) {

    public ArtifactReference {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(uri, "uri");
        Objects.requireNonNull(expiresAt, "expiresAt");
        if (!"artifact".equalsIgnoreCase(uri.getScheme())) {
            throw new IllegalArgumentException("artifact URI must use artifact scheme");
        }
        if (characters < 0) {
            throw new IllegalArgumentException("artifact character count must not be negative");
        }
    }
}
