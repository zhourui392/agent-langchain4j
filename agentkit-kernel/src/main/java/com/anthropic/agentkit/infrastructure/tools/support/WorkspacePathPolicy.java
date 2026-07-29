package com.anthropic.agentkit.infrastructure.tools.support;

import java.nio.file.Path;
import java.util.Objects;

/** Platform-neutral containment policy over paths supplied by a filesystem resolver. */
final class WorkspacePathPolicy {

    void requireWithin(Path root, Path candidate, String requested, String reason) {
        Objects.requireNonNull(root, "root");
        Objects.requireNonNull(candidate, "candidate");
        if (!candidate.startsWith(root)) {
            throw WorkspaceBoundaryViolationException.rejected(requested, reason);
        }
    }

    boolean contains(Path root, Path candidate) {
        Objects.requireNonNull(root, "root");
        Objects.requireNonNull(candidate, "candidate");
        return candidate.startsWith(root);
    }
}
