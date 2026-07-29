package com.anthropic.agentkit.infrastructure.mcp;

import com.anthropic.agentkit.domain.agent.AgentBudget;
import com.anthropic.agentkit.domain.agent.RunId;
import com.anthropic.agentkit.domain.agent.WorkspaceId;
import com.anthropic.agentkit.domain.conversation.CancellationToken;
import com.anthropic.agentkit.domain.tool.ExecutionContext;
import com.anthropic.agentkit.domain.tool.Tool;
import com.anthropic.agentkit.domain.tool.ToolArguments;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class StdioMcpTransportIT {

    @TempDir
    Path directory;

    @Test
    void discoversCallsAndClosesRealStdioServer() throws Exception {
        Path closeMarker = directory.resolve("closed.txt");
        McpServerConfig config = McpServerConfig.stdio("stdio", serverCommand())
                .withEnvironment("FAKE_MCP_CLOSED_FILE", closeMarker.toString())
                .withInitializationTimeout(Duration.ofSeconds(3))
                .withCallTimeout(Duration.ofSeconds(3));
        ExecutionContext context = context();

        try (McpServerManager manager = new McpServerManager(List.of(config))) {
            Tool echo = manager.snapshot(context).tools().getFirst();

            assertThat(echo.name()).isEqualTo("stdio.echo");
            assertThat(echo.isReadOnly()).isTrue();
            assertThat(echo.execute(
                    ToolArguments.of(Map.of("value", "hello")), context).content())
                    .isEqualTo("echo:hello");
            manager.close(context);
        }

        assertThat(awaitMarker(closeMarker)).isTrue();
    }

    private static List<String> serverCommand() {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        return List.of(java, "-cp", System.getProperty("java.class.path"),
                FakeStdioMcpServer.class.getName());
    }

    private static ExecutionContext context() {
        Path root = Path.of(".").toAbsolutePath();
        return ExecutionContext.of(RunId.of("stdio-run"), WorkspaceId.fromPath(root), root,
                new CancellationToken(), AgentBudget.unlimited());
    }

    private static boolean awaitMarker(Path marker) throws InterruptedException {
        for (int attempt = 0; attempt < 100 && Files.notExists(marker); attempt++) {
            Thread.sleep(10);
        }
        return Files.exists(marker);
    }
}
