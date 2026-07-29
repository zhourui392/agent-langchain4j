package com.anthropic.agentkit.infrastructure.tools;

import com.anthropic.agentkit.domain.agent.AgentBudget;
import com.anthropic.agentkit.domain.agent.AgentRunContext;
import com.anthropic.agentkit.domain.agent.RunId;
import com.anthropic.agentkit.domain.agent.WorkspaceId;
import com.anthropic.agentkit.domain.conversation.CancellationToken;
import com.anthropic.agentkit.domain.conversation.SessionId;
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

class FileWriteToolTest {

    @Test
    void createsNewFile(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("new.txt");
        FileWriteTool tool = new FileWriteTool(new FileStateCache());

        ToolResult result = tool.execute(
                ToolArguments.of(Map.of("path", file.toString(), "content", "hello")),
                ExecutionContext.at(dir));

        assertThat(result.success()).isTrue();
        assertThat(Files.readString(file)).isEqualTo("hello");
    }

    @Test
    void overwritesExistingFileAfterRead(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("existing.txt");
        Files.writeString(file, "old");
        FileStateCache cache = new FileStateCache();
        cache.recordRead(file);

        FileWriteTool tool = new FileWriteTool(cache);
        ToolResult result = tool.execute(
                ToolArguments.of(Map.of("path", file.toString(), "content", "new")),
                ExecutionContext.at(dir));

        assertThat(result.success()).isTrue();
        assertThat(Files.readString(file)).isEqualTo("new");
    }

    @Test
    void createsParentDirectoriesIfMissing(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("a/b/c/deep.txt");
        FileWriteTool tool = new FileWriteTool(new FileStateCache());

        ToolResult result = tool.execute(
                ToolArguments.of(Map.of("path", file.toString(), "content", "deep")),
                ExecutionContext.at(dir));

        assertThat(result.success()).isTrue();
        assertThat(Files.readString(file)).isEqualTo("deep");
    }

    @Test
    void requiresReadBeforeOverwrite(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("existing.txt");
        Files.writeString(file, "untouched");
        FileWriteTool tool = new FileWriteTool(new FileStateCache());

        ToolResult result = tool.execute(
                ToolArguments.of(Map.of("path", file.toString(), "content", "new")),
                ExecutionContext.at(dir));

        assertThat(result.success()).isFalse();
        assertThat(result.content()).contains("must Read");
        assertThat(Files.readString(file)).isEqualTo("untouched");
    }

    @Test
    void readAuthorizationDoesNotLeakAcrossWorkspaces(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("existing.txt");
        Files.writeString(file, "old");
        FileStateCache cache = new FileStateCache();
        FileReadTool read = new FileReadTool(cache);
        FileWriteTool write = new FileWriteTool(cache);

        read.execute(ToolArguments.of(Map.of("path", file.toString())),
                executionContext("read-run", "workspace-a", dir));
        ToolResult result = write.execute(
                ToolArguments.of(Map.of("path", file.toString(), "content", "new")),
                executionContext("write-run", "workspace-b", dir));

        assertThat(result.success()).isFalse();
        assertThat(result.content()).contains("must Read");
        assertThat(Files.readString(file)).isEqualTo("old");
    }

    private static ExecutionContext executionContext(String runId, String workspaceId, Path root) {
        return AgentRunContext.of(
                RunId.of(runId), SessionId.of(runId + "-session"), WorkspaceId.of(workspaceId),
                root, new CancellationToken(), AgentBudget.unlimited()).executionContext();
    }
}
