package com.anthropic.agentkit.infrastructure.tools;

import com.anthropic.agentkit.domain.tool.ExecutionContext;
import com.anthropic.agentkit.domain.tool.ToolArguments;
import com.anthropic.agentkit.domain.tool.ToolResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class GlobToolTest {

    @Test
    void matchesSimpleStarPattern(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("a.txt"), "");
        Files.writeString(dir.resolve("b.txt"), "");
        Files.writeString(dir.resolve("c.md"), "");

        ToolResult result = new GlobTool().execute(
                ToolArguments.of(Map.of("pattern", "*.txt")),
                ExecutionContext.at(dir));

        assertThat(result.success()).isTrue();
        assertThat(result.content()).contains("a.txt", "b.txt");
        assertThat(result.content()).doesNotContain("c.md");
    }

    @Test
    void matchesDoubleStarRecursive(@TempDir Path dir) throws IOException {
        Files.createDirectories(dir.resolve("nested/deep"));
        Files.writeString(dir.resolve("nested/deep/x.java"), "");
        Files.writeString(dir.resolve("nested/y.java"), "");
        Files.writeString(dir.resolve("z.java"), "");

        ToolResult result = new GlobTool().execute(
                ToolArguments.of(Map.of("pattern", "**/*.java")),
                ExecutionContext.at(dir));

        assertThat(result.success()).isTrue();
        assertThat(result.content()).contains("z.java");
        assertThat(result.content()).containsAnyOf("nested/y.java", "nested\\y.java");
        assertThat(result.content()).containsAnyOf("nested/deep/x.java", "nested\\deep\\x.java");
    }

    @Test
    void sortsByModificationTimeDescending(@TempDir Path dir) throws IOException {
        Path old = dir.resolve("old.txt");
        Path mid = dir.resolve("mid.txt");
        Path recent = dir.resolve("recent.txt");
        Files.writeString(old, "");
        Files.writeString(mid, "");
        Files.writeString(recent, "");
        Files.setLastModifiedTime(old, FileTime.from(Instant.now().minusSeconds(300)));
        Files.setLastModifiedTime(mid, FileTime.from(Instant.now().minusSeconds(100)));
        Files.setLastModifiedTime(recent, FileTime.from(Instant.now()));

        ToolResult result = new GlobTool().execute(
                ToolArguments.of(Map.of("pattern", "*.txt")),
                ExecutionContext.at(dir));

        String body = result.content();
        int recentIdx = body.indexOf("recent.txt");
        int midIdx = body.indexOf("mid.txt");
        int oldIdx = body.indexOf("old.txt");
        assertThat(recentIdx).isLessThan(midIdx);
        assertThat(midIdx).isLessThan(oldIdx);
    }

    @Test
    void respectsGitignoreByDefault(@TempDir Path dir) throws IOException {
        Files.createDirectories(dir.resolve(".git/objects"));
        Files.writeString(dir.resolve(".git/objects/blob.txt"), "");
        Files.writeString(dir.resolve("real.txt"), "");

        ToolResult result = new GlobTool().execute(
                ToolArguments.of(Map.of("pattern", "**/*.txt")),
                ExecutionContext.at(dir));

        assertThat(result.success()).isTrue();
        assertThat(result.content()).contains("real.txt");
        assertThat(result.content()).doesNotContain("blob.txt");
    }
}
