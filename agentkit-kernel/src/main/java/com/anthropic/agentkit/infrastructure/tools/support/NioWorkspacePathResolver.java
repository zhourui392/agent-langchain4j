package com.anthropic.agentkit.infrastructure.tools.support;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Objects;

/** Resolves workspace paths with the semantics of the current NIO filesystem provider. */
final class NioWorkspacePathResolver {

    Path normalizeRoot(Path workspaceRoot) {
        return Objects.requireNonNull(workspaceRoot, "workspaceRoot")
                .toAbsolutePath().normalize();
    }

    Path resolve(Path normalizedRoot, String requested) {
        Objects.requireNonNull(normalizedRoot, "normalizedRoot");
        Path request = normalizedRoot.getFileSystem().getPath(requireRequested(requested));
        return request.isAbsolute()
                ? request.normalize()
                : normalizedRoot.resolve(request).normalize();
    }

    Path realPath(Path path) throws IOException {
        return Objects.requireNonNull(path, "path").toRealPath();
    }

    Path nearestExisting(Path target) {
        Path current = Objects.requireNonNull(target, "target");
        while (current != null && !Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
            current = current.getParent();
        }
        if (current == null) {
            throw WorkspaceBoundaryViolationException.rejected(
                    target.toString(), "no existing parent");
        }
        return current;
    }

    private static String requireRequested(String requested) {
        if (requested == null || requested.isBlank()) {
            throw WorkspaceBoundaryViolationException.rejected(
                    String.valueOf(requested), "path must not be blank");
        }
        return requested;
    }
}
