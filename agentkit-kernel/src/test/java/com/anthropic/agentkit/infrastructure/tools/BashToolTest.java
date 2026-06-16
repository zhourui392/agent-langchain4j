package com.anthropic.agentkit.infrastructure.tools;

import com.anthropic.agentkit.domain.conversation.CancellationToken;
import com.anthropic.agentkit.domain.tool.ExecutionContext;
import com.anthropic.agentkit.domain.tool.ToolArguments;
import com.anthropic.agentkit.domain.tool.ToolResult;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class BashToolTest {

    private static final Path CWD = Paths.get(System.getProperty("user.dir", "."));

    @Test
    void capturesStdoutOnSuccess() {
        BashTool tool = new BashTool();
        ToolResult result = tool.execute(
                ToolArguments.of(Map.of("command", "echo hi")),
                ExecutionContext.at(CWD));

        assertThat(result.success()).isTrue();
        assertThat(result.content().trim()).isEqualTo("hi");
    }

    @Test
    void reportsNonZeroExit() {
        BashTool tool = new BashTool();
        ToolResult result = tool.execute(
                ToolArguments.of(Map.of("command", "exit 7")),
                ExecutionContext.at(CWD));

        assertThat(result.success()).isFalse();
        assertThat(result.content()).contains("exit 7");
    }

    @Test
    void killsOnTimeout() {
        BashTool tool = new BashTool();
        long start = System.currentTimeMillis();
        ToolResult result = tool.execute(
                ToolArguments.of(Map.of("command", "sleep 10", "timeout", 500)),
                ExecutionContext.at(CWD));
        long elapsed = System.currentTimeMillis() - start;

        assertThat(result.success()).isFalse();
        assertThat(result.content()).contains("timeout");
        assertThat(elapsed).isLessThan(3000);
    }

    @Test
    void propagatesCancellation() {
        BashTool tool = new BashTool();
        CancellationToken cancel = new CancellationToken();
        new Thread(() -> {
            try { Thread.sleep(200); } catch (InterruptedException ignored) {}
            cancel.cancel();
        }).start();

        long start = System.currentTimeMillis();
        ToolResult result = tool.execute(
                ToolArguments.of(Map.of("command", "sleep 10", "timeout", 30_000)),
                ExecutionContext.of(CWD, cancel));
        long elapsed = System.currentTimeMillis() - start;

        assertThat(result.success()).isFalse();
        assertThat(elapsed).isLessThan(3000);
    }

    @Test
    void capturesStderrCombined() {
        BashTool tool = new BashTool();
        ToolResult result = tool.execute(
                ToolArguments.of(Map.of("command", "echo err 1>&2")),
                ExecutionContext.at(CWD));

        assertThat(result.success()).isTrue();
        assertThat(result.content()).contains("err");
    }
}
