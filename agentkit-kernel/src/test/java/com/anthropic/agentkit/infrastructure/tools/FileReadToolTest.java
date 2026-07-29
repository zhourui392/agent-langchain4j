package com.anthropic.agentkit.infrastructure.tools;

import com.anthropic.agentkit.domain.agent.AgentBudget;
import com.anthropic.agentkit.domain.agent.RunId;
import com.anthropic.agentkit.domain.agent.WorkspaceId;
import com.anthropic.agentkit.domain.conversation.CancellationToken;
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
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class FileReadToolTest {

    @Test
    void readsUtf8Content(@TempDir Path dir) throws IOException {
        Path file = writeFile(dir, "a.txt", "héllo 世界");
        FileReadTool tool = new FileReadTool(new FileStateCache());

        ToolResult result = tool.execute(
                ToolArguments.of(Map.of("path", file.toString())),
                context(dir));

        assertThat(result.success()).isTrue();
        assertThat(result.content()).contains("héllo 世界");
    }

    @Test
    void returnsErrorWhenFileMissing(@TempDir Path dir) {
        FileReadTool tool = new FileReadTool(new FileStateCache());

        ToolResult result = tool.execute(
                ToolArguments.of(Map.of("path", dir.resolve("missing.txt").toString())),
                context(dir));

        assertThat(result.success()).isFalse();
        assertThat(result.content()).contains("not found");
    }

    @Test
    void truncatesAtMaxLines(@TempDir Path dir) throws IOException {
        String content = IntStream.range(0, 5000)
                .mapToObj(i -> "line " + i).reduce((a, b) -> a + "\n" + b).orElseThrow();
        Path file = writeFile(dir, "big.txt", content);
        FileReadTool tool = new FileReadTool(new FileStateCache());

        ToolResult result = tool.execute(
                ToolArguments.of(Map.of("path", file.toString())),
                context(dir));

        assertThat(result.success()).isTrue();
        assertThat(result.content()).contains("line 1999");
        assertThat(result.content()).doesNotContain("line 2001");
        assertThat(result.content()).contains("truncated");
    }

    @Test
    void respectsCustomMaxLines(@TempDir Path dir) throws IOException {
        String content = "a\nb\nc\nd\ne";
        Path file = writeFile(dir, "small.txt", content);
        FileReadTool tool = new FileReadTool(new FileStateCache());

        ToolResult result = tool.execute(
                ToolArguments.of(Map.of("path", file.toString(), "maxLines", 2)),
                context(dir));

        assertThat(result.success()).isTrue();
        assertThat(result.content()).contains("a\nb");
        assertThat(result.content()).doesNotContain("\nc");
    }

    @Test
    void recordsReadInFileStateCache(@TempDir Path dir) throws IOException {
        Path file = writeFile(dir, "a.txt", "hello");
        FileStateCache cache = new FileStateCache();
        FileReadTool tool = new FileReadTool(cache);

        tool.execute(
                ToolArguments.of(Map.of("path", file.toString())),
                context(dir));

        assertThat(cache.hasBeenRead(context(dir), file)).isTrue();
    }

    private static Path writeFile(Path dir, String name, String content) throws IOException {
        Path file = dir.resolve(name);
        Files.writeString(file, content);
        return file;
    }

    private static ExecutionContext context(Path dir) {
        return ExecutionContext.of(
                RunId.of("file-read-test"), WorkspaceId.fromPath(dir), dir,
                new CancellationToken(), AgentBudget.unlimited());
    }
}
