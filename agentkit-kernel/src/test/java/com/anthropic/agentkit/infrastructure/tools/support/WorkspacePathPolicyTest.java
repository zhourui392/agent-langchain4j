package com.anthropic.agentkit.infrastructure.tools.support;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorkspacePathPolicyTest {

    private final WorkspacePathPolicy policy = new WorkspacePathPolicy();

    @Test
    void acceptsRootAndDescendantButRejectsSibling() {
        Path root = Path.of("workspace").toAbsolutePath().normalize();

        assertThat(policy.contains(root, root)).isTrue();
        assertThat(policy.contains(root, root.resolve("src/Main.java"))).isTrue();
        assertThat(policy.contains(root, root.resolveSibling("workspace-other"))).isFalse();
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void windowsDriveComparisonUsesWindowsPathSemantics() {
        Path root = Path.of("C:\\workspace");
        Path otherDrive = Path.of("D:\\workspace\\secret.txt");

        assertThatThrownBy(() -> policy.requireWithin(
                root, otherDrive, otherDrive.toString(), "path escapes workspace"))
                .isInstanceOf(WorkspaceBoundaryViolationException.class);
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void windowsUncComparisonUsesWindowsPathSemantics() {
        Path root = Path.of("\\\\server\\share\\workspace");
        Path otherShare = Path.of("\\\\server\\other\\secret.txt");

        assertThatThrownBy(() -> policy.requireWithin(
                root, otherShare, otherShare.toString(), "path escapes workspace"))
                .isInstanceOf(WorkspaceBoundaryViolationException.class);
    }
}
