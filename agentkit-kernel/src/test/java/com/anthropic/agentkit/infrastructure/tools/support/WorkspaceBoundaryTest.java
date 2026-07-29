package com.anthropic.agentkit.infrastructure.tools.support;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorkspaceBoundaryTest {

    private final WorkspaceBoundary boundary = new WorkspaceBoundary();

    @Test
    void acceptsAbsolutePathWhenItRemainsInsideWorkspace(@TempDir Path workspace)
            throws IOException {
        Path file = Files.writeString(workspace.resolve("inside.txt"), "inside");

        Path resolved = boundary.resolveExisting(workspace, file.toAbsolutePath().toString());

        assertThat(resolved).isEqualTo(file.toRealPath());
    }

    @Test
    void rejectsAbsolutePathFromSiblingRoot(@TempDir Path root) throws IOException {
        Path workspace = Files.createDirectory(root.resolve("workspace"));
        Path outside = Files.writeString(root.resolve("outside.txt"), "outside");

        assertThatThrownBy(() -> boundary.resolveExisting(workspace, outside.toString()))
                .isInstanceOf(WorkspaceBoundaryViolationException.class)
                .hasMessageContaining("path escapes workspace");
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void canonicalizesAllowedSymlinkParentBeforeCreating(@TempDir Path workspace)
            throws IOException {
        Path realDirectory = Files.createDirectory(workspace.resolve("real"));
        Files.createSymbolicLink(workspace.resolve("alias"), realDirectory);

        Path resolved = boundary.resolveForCreate(workspace, "alias/new.txt");

        assertThat(resolved).isEqualTo(realDirectory.toRealPath().resolve("new.txt"));
    }

    @Test
    void rejectsBlankRequest(@TempDir Path workspace) {
        assertThatThrownBy(() -> boundary.resolveForCreate(workspace, " "))
                .isInstanceOf(WorkspaceBoundaryViolationException.class)
                .hasMessageContaining("must not be blank");
    }
}
