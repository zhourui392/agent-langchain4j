package com.anthropic.agentkit.infrastructure.tools.support;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;

/** Resolves paths while enforcing the real filesystem boundary of one workspace. */
public final class WorkspaceBoundary {

    private final WorkspacePathPolicy policy;
    private final NioWorkspacePathResolver resolver;

    public WorkspaceBoundary() {
        this(new WorkspacePathPolicy(), new NioWorkspacePathResolver());
    }

    WorkspaceBoundary(
            WorkspacePathPolicy policy, NioWorkspacePathResolver resolver) {
        this.policy = Objects.requireNonNull(policy, "policy");
        this.resolver = Objects.requireNonNull(resolver, "resolver");
    }

    public Path resolveExisting(Path workspaceRoot, String requested) throws IOException {
        Path lexical = resolveLexically(workspaceRoot, requested);
        Path target = resolver.realPath(lexical);
        policy.requireWithin(realRoot(workspaceRoot), target, requested,
                "real path escapes workspace");
        return target;
    }

    public Path resolveForCreate(Path workspaceRoot, String requested) throws IOException {
        Path lexical = resolveLexically(workspaceRoot, requested);
        Path existingAncestor = resolver.nearestExisting(lexical);
        Path realAncestor = resolver.realPath(existingAncestor);
        policy.requireWithin(realRoot(workspaceRoot), realAncestor, requested,
                "real parent path escapes workspace");
        return realAncestor.resolve(existingAncestor.relativize(lexical)).normalize();
    }

    public boolean containsExisting(Path workspaceRoot, Path candidate) {
        try {
            Path root = realRoot(workspaceRoot);
            Path target = resolver.realPath(Objects.requireNonNull(candidate, "candidate"));
            return policy.contains(root, target);
        } catch (IOException | RuntimeException ex) {
            return false;
        }
    }

    private Path resolveLexically(Path workspaceRoot, String requested) {
        Path root = resolver.normalizeRoot(workspaceRoot);
        Path resolved = resolver.resolve(root, requested);
        policy.requireWithin(root, resolved, requested, "path escapes workspace");
        return resolved;
    }

    private Path realRoot(Path workspaceRoot) throws IOException {
        return resolver.realPath(resolver.normalizeRoot(workspaceRoot));
    }
}
