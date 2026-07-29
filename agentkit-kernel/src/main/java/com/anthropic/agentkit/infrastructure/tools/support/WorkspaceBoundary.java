package com.anthropic.agentkit.infrastructure.tools.support;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Objects;

/** Resolves paths while enforcing the real filesystem boundary of one workspace. */
public final class WorkspaceBoundary {

    public Path resolveExisting(Path workspaceRoot, String requested) throws IOException {
        Path lexical = resolveLexically(workspaceRoot, requested);
        Path target = lexical.toRealPath();
        requireWithin(realRoot(workspaceRoot), target, requested);
        return target;
    }

    public Path resolveForCreate(Path workspaceRoot, String requested) throws IOException {
        Path lexical = resolveLexically(workspaceRoot, requested);
        Path existingAncestor = nearestExisting(lexical);
        requireWithin(realRoot(workspaceRoot), existingAncestor.toRealPath(), requested);
        return lexical;
    }

    public boolean containsExisting(Path workspaceRoot, Path candidate) {
        try {
            Path root = realRoot(workspaceRoot);
            Path target = Objects.requireNonNull(candidate, "candidate").toRealPath();
            return target.startsWith(root);
        } catch (IOException | RuntimeException ex) {
            return false;
        }
    }

    private static Path resolveLexically(Path workspaceRoot, String requested) {
        Path root = normalizeRoot(workspaceRoot);
        Path request = Path.of(requireRequested(requested));
        Path resolved = request.isAbsolute()
                ? request.normalize()
                : root.resolve(request).normalize();
        if (!resolved.startsWith(root)) {
            throw violation(requested, "path escapes workspace");
        }
        return resolved;
    }

    private static Path realRoot(Path workspaceRoot) throws IOException {
        return normalizeRoot(workspaceRoot).toRealPath();
    }

    private static Path normalizeRoot(Path workspaceRoot) {
        return Objects.requireNonNull(workspaceRoot, "workspaceRoot")
                .toAbsolutePath().normalize();
    }

    private static Path nearestExisting(Path target) {
        Path current = target;
        while (current != null && !Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
            current = current.getParent();
        }
        if (current == null) {
            throw violation(target.toString(), "no existing parent");
        }
        return current;
    }

    private static void requireWithin(Path root, Path target, String requested) {
        if (!target.startsWith(root)) {
            throw violation(requested, "real path escapes workspace");
        }
    }

    private static String requireRequested(String requested) {
        if (requested == null || requested.isBlank()) {
            throw violation(String.valueOf(requested), "path must not be blank");
        }
        return requested;
    }

    private static WorkspaceBoundaryViolationException violation(String requested, String reason) {
        return new WorkspaceBoundaryViolationException(
                "workspace boundary rejected path '" + requested + "': " + reason);
    }
}
