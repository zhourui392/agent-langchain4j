package com.anthropic.cclc.infrastructure.tools;

import com.anthropic.cclc.domain.tool.ExecutionContext;
import com.anthropic.cclc.domain.tool.ToolArguments;
import com.anthropic.cclc.domain.tool.ToolResult;
import com.anthropic.cclc.infrastructure.tools.support.JavaRegexGrepBackend;
import com.anthropic.cclc.infrastructure.tools.support.RipgrepBackend;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class GrepToolTest {

    @Test
    void fallsBackToJavaWhenRipgrepMissing(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("a.txt"), "alpha\nbeta\ngamma");
        Files.writeString(dir.resolve("b.txt"), "delta");

        ToolResult result = new GrepTool(new JavaRegexGrepBackend()).execute(
                ToolArguments.of(Map.of("pattern", "beta")),
                ExecutionContext.at(dir));

        assertThat(result.success()).isTrue();
        assertThat(result.content()).contains("a.txt").contains("beta");
        assertThat(result.content()).doesNotContain("delta");
    }

    @Test
    void javaBackendSupportsContextLines(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("a.txt"), "line1\nline2\nMATCH\nline4\nline5");

        ToolResult result = new GrepTool(new JavaRegexGrepBackend()).execute(
                ToolArguments.of(Map.of("pattern", "MATCH", "context", 1)),
                ExecutionContext.at(dir));

        assertThat(result.success()).isTrue();
        assertThat(result.content()).contains("line2", "MATCH", "line4");
        assertThat(result.content()).doesNotContain("line1");
    }

    @Test
    void javaBackendSupportsGlobFilter(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("a.java"), "needle in java");
        Files.writeString(dir.resolve("b.txt"), "needle in txt");

        ToolResult result = new GrepTool(new JavaRegexGrepBackend()).execute(
                ToolArguments.of(Map.of("pattern", "needle", "glob", "*.java")),
                ExecutionContext.at(dir));

        assertThat(result.success()).isTrue();
        assertThat(result.content()).contains("a.java");
        assertThat(result.content()).doesNotContain("b.txt");
    }

    @Test
    void javaBackendReturnsEmptyWhenNoMatches(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("a.txt"), "alpha");

        ToolResult result = new GrepTool(new JavaRegexGrepBackend()).execute(
                ToolArguments.of(Map.of("pattern", "nothere")),
                ExecutionContext.at(dir));

        assertThat(result.success()).isTrue();
        assertThat(result.content()).contains("no matches");
    }

    @Test
    @EnabledIf("ripgrepAvailable")
    void findsMatchesWithRipgrepWhenAvailable(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("a.txt"), "alpha\nbeta\ngamma");

        ToolResult result = new GrepTool(new RipgrepBackend()).execute(
                ToolArguments.of(Map.of("pattern", "beta")),
                ExecutionContext.at(dir));

        assertThat(result.success()).isTrue();
        assertThat(result.content()).contains("beta");
    }

    static boolean ripgrepAvailable() {
        return RipgrepBackend.isAvailable();
    }
}
