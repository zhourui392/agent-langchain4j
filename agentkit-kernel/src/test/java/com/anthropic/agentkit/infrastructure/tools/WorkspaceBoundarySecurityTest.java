package com.anthropic.agentkit.infrastructure.tools;

import com.anthropic.agentkit.domain.tool.ExecutionContext;
import com.anthropic.agentkit.domain.tool.ToolArguments;
import com.anthropic.agentkit.domain.tool.ToolResult;
import com.anthropic.agentkit.infrastructure.tools.support.FileStateCache;
import com.anthropic.agentkit.infrastructure.tools.support.JavaRegexGrepBackend;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class WorkspaceBoundarySecurityTest {

    @Test
    void readCannotEscapeWorkspaceThroughDotDot(@TempDir Path root) throws IOException {
        Path workspace = Files.createDirectory(root.resolve("workspace"));
        Files.writeString(root.resolve("secret.txt"), "outside-secret");

        ToolResult result = new FileReadTool(new FileStateCache()).execute(
                args("path", "../secret.txt"), ExecutionContext.at(workspace));

        assertBoundaryRejection(result);
        assertThat(result.content()).doesNotContain("outside-secret");
    }

    @Test
    void writeCannotEscapeWorkspaceThroughAbsolutePath(@TempDir Path root) throws IOException {
        Path workspace = Files.createDirectory(root.resolve("workspace"));
        Path outside = root.resolve("outside.txt").toAbsolutePath();

        ToolResult result = new FileWriteTool(new FileStateCache()).execute(
                ToolArguments.of(Map.of("path", outside.toString(), "content", "escaped")),
                ExecutionContext.at(workspace));

        assertBoundaryRejection(result);
        assertThat(outside).doesNotExist();
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void writeCannotEscapeWorkspaceThroughSymlinkedParent(@TempDir Path root) throws IOException {
        Path workspace = Files.createDirectory(root.resolve("workspace"));
        Path outside = Files.createDirectory(root.resolve("outside"));
        Files.createSymbolicLink(workspace.resolve("linked"), outside);

        ToolResult result = new FileWriteTool(new FileStateCache()).execute(
                ToolArguments.of(Map.of("path", "linked/pwned.txt", "content", "escaped")),
                ExecutionContext.at(workspace));

        assertBoundaryRejection(result);
        assertThat(outside.resolve("pwned.txt")).doesNotExist();
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void editRejectsSymlinkTargetOutsideWorkspace(@TempDir Path root) throws IOException {
        Path workspace = Files.createDirectory(root.resolve("workspace"));
        Path outside = root.resolve("outside.txt");
        Files.writeString(outside, "before");
        Path linked = Files.createSymbolicLink(workspace.resolve("linked.txt"), outside);
        ExecutionContext context = ExecutionContext.at(workspace);
        FileStateCache cache = new FileStateCache();
        cache.recordRead(context, linked);

        ToolResult result = new FileEditTool(cache).execute(ToolArguments.of(Map.of(
                "path", "linked.txt", "old_string", "before", "new_string", "after")), context);

        assertBoundaryRejection(result);
        assertThat(outside).hasContent("before");
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void globAndGrepCannotTraverseOutsideWorkspace(@TempDir Path root) throws IOException {
        Path workspace = Files.createDirectory(root.resolve("workspace"));
        Path outside = root.resolve("outside.txt");
        Files.writeString(outside, "outside-secret-marker");
        Files.createSymbolicLink(workspace.resolve("leak.txt"), outside);
        ExecutionContext context = ExecutionContext.at(workspace);

        ToolResult glob = new GlobTool().execute(args("pattern", "*.txt"), context);
        ToolResult grep = new GrepTool(new JavaRegexGrepBackend()).execute(
                args("pattern", "outside-secret-marker"), context);

        assertThat(glob.content()).doesNotContain("leak.txt");
        assertThat(grep.content()).doesNotContain("outside-secret-marker", "leak.txt");
    }

    private static ToolArguments args(String key, String value) {
        return ToolArguments.of(Map.of(key, value));
    }

    private static void assertBoundaryRejection(ToolResult result) {
        assertThat(result.success()).isFalse();
        assertThat(result.content()).containsIgnoringCase("workspace");
    }
}
