package com.anthropic.agentkit.infrastructure.tools;

import com.anthropic.agentkit.domain.tool.ExecutionContext;
import com.anthropic.agentkit.domain.tool.ToolArguments;
import com.anthropic.agentkit.domain.tool.ToolResult;
import com.anthropic.agentkit.infrastructure.tools.support.FileStateCache;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class FileEditToolTest {

    @Test
    void replacesUniqueOccurrence(@TempDir Path dir) throws IOException {
        Path file = writeFile(dir, "a.txt", "hello world");
        FileStateCache cache = new FileStateCache();
        cache.recordRead(file);

        FileEditTool tool = new FileEditTool(cache);
        ToolResult result = tool.execute(
                ToolArguments.of(Map.of(
                        "path", file.toString(),
                        "old_string", "world",
                        "new_string", "Java")),
                ExecutionContext.at(dir));

        assertThat(result.success()).isTrue();
        assertThat(Files.readString(file)).isEqualTo("hello Java");
    }

    @Test
    void rejectsWhenOldStringAppearsMultipleTimes(@TempDir Path dir) throws IOException {
        Path file = writeFile(dir, "a.txt", "foo foo foo");
        FileStateCache cache = new FileStateCache();
        cache.recordRead(file);

        FileEditTool tool = new FileEditTool(cache);
        ToolResult result = tool.execute(
                ToolArguments.of(Map.of(
                        "path", file.toString(),
                        "old_string", "foo",
                        "new_string", "bar")),
                ExecutionContext.at(dir));

        assertThat(result.success()).isFalse();
        assertThat(result.content()).contains("3 times");
        assertThat(Files.readString(file)).isEqualTo("foo foo foo");
    }

    @Test
    void rejectsWhenFileNotReadFirst(@TempDir Path dir) throws IOException {
        Path file = writeFile(dir, "a.txt", "hello");
        FileEditTool tool = new FileEditTool(new FileStateCache());

        ToolResult result = tool.execute(
                ToolArguments.of(Map.of(
                        "path", file.toString(),
                        "old_string", "hello",
                        "new_string", "hi")),
                ExecutionContext.at(dir));

        assertThat(result.success()).isFalse();
        assertThat(result.content()).contains("must Read");
        assertThat(Files.readString(file)).isEqualTo("hello");
    }

    @Test
    void replaceAllModeReplacesEveryOccurrence(@TempDir Path dir) throws IOException {
        Path file = writeFile(dir, "a.txt", "foo foo foo");
        FileStateCache cache = new FileStateCache();
        cache.recordRead(file);

        FileEditTool tool = new FileEditTool(cache);
        ToolResult result = tool.execute(
                ToolArguments.of(Map.of(
                        "path", file.toString(),
                        "old_string", "foo",
                        "new_string", "bar",
                        "replace_all", true)),
                ExecutionContext.at(dir));

        assertThat(result.success()).isTrue();
        assertThat(Files.readString(file)).isEqualTo("bar bar bar");
    }

    @Test
    void producesUnifiedDiff(@TempDir Path dir) throws IOException {
        Path file = writeFile(dir, "a.txt", "line1\nline2\nline3");
        FileStateCache cache = new FileStateCache();
        cache.recordRead(file);

        FileEditTool tool = new FileEditTool(cache);
        ToolResult result = tool.execute(
                ToolArguments.of(Map.of(
                        "path", file.toString(),
                        "old_string", "line2",
                        "new_string", "LINE-TWO")),
                ExecutionContext.at(dir));

        assertThat(result.success()).isTrue();
        assertThat(result.content()).contains("---");
        assertThat(result.content()).contains("+++");
        assertThat(result.content()).contains("@@");
        assertThat(result.content()).contains("-line2");
        assertThat(result.content()).contains("+LINE-TWO");
    }

    @Test
    void reportsErrorWhenOldStringNotFound(@TempDir Path dir) throws IOException {
        Path file = writeFile(dir, "a.txt", "hello");
        FileStateCache cache = new FileStateCache();
        cache.recordRead(file);

        FileEditTool tool = new FileEditTool(cache);
        ToolResult result = tool.execute(
                ToolArguments.of(Map.of(
                        "path", file.toString(),
                        "old_string", "missing",
                        "new_string", "x")),
                ExecutionContext.at(dir));

        assertThat(result.success()).isFalse();
        assertThat(result.content()).contains("not found");
    }

    private static Path writeFile(Path dir, String name, String content) throws IOException {
        Path file = dir.resolve(name);
        Files.writeString(file, content);
        return file;
    }
}
