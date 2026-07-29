package com.anthropic.agentkit.infrastructure.tools.support;

import com.anthropic.agentkit.domain.conversation.CancellationToken;
import com.anthropic.agentkit.domain.tool.ToolResult;
import com.anthropic.agentkit.domain.tool.ToolResultStatus;
import org.zeroturnaround.exec.ProcessExecutor;
import org.zeroturnaround.exec.ProcessResult;
import org.zeroturnaround.exec.StartedProcess;

import java.nio.file.Path;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public final class ProcessRunner {

    public ToolResult run(String command, Path cwd, int timeoutMs, CancellationToken cancel) {
        try {
            ProcessExecutor executor = new ProcessExecutor()
                    .command(ShellSelector.commandFor(command))
                    .directory(cwd.toFile())
                    .timeout(timeoutMs, TimeUnit.MILLISECONDS)
                    .redirectErrorStream(true)
                    .readOutput(true)
                    .exitValueAny();

            StartedProcess started = executor.start();
            cancel.onCancel(() -> started.getProcess().destroyForcibly());

            ProcessResult result = started.getFuture().get(timeoutMs, TimeUnit.MILLISECONDS);
            String output = result.outputUTF8();
            if (cancel.isCancelled()) {
                return ToolResult.of(ToolResultStatus.CANCELLED, "cancelled\n" + output);
            }
            if (result.getExitValue() != 0) {
                return ToolResult.error("exit " + result.getExitValue() + "\n" + output);
            }
            return ToolResult.ok(output);
        } catch (TimeoutException ex) {
            return ToolResult.of(ToolResultStatus.TIMEOUT, "timeout after " + timeoutMs + "ms");
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return ToolResult.of(ToolResultStatus.CANCELLED, "interrupted");
        } catch (ExecutionException ex) {
            return ToolResult.error("process error: " + ex.getCause().getMessage());
        } catch (Exception ex) {
            return ToolResult.error("process error: " + ex.getMessage());
        }
    }
}
