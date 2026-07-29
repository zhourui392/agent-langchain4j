package com.anthropic.agentkit.infrastructure.task;

import com.anthropic.agentkit.domain.task.OutputChunk;
import com.anthropic.agentkit.domain.task.OutputCursor;
import com.anthropic.agentkit.domain.task.TaskHandle;
import com.anthropic.agentkit.domain.task.TaskId;
import com.anthropic.agentkit.domain.task.TaskLaunchSpec;
import com.anthropic.agentkit.domain.task.TaskScope;
import com.anthropic.agentkit.domain.task.TaskState;
import com.anthropic.agentkit.domain.tool.ToolResult;
import com.anthropic.agentkit.domain.tool.ToolResultStatus;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Process-backed task aggregate with append-only output and exactly-once completion. */
final class ProcessTaskHandle implements TaskHandle {

    private final TaskLaunchSpec spec;
    private final StringBuilder output = new StringBuilder();
    private final CompletableFuture<ToolResult> completion = new CompletableFuture<>();
    private final AtomicReference<TaskState> state =
            new AtomicReference<>(TaskState.STARTING);
    private final AtomicReference<TaskState> terminalClaim = new AtomicReference<>();
    private volatile Process process;
    private volatile Thread outputReader;

    ProcessTaskHandle(TaskLaunchSpec spec) {
        this.spec = spec;
    }

    void start() {
        try {
            process = new ProcessBuilder(spec.command())
                    .directory(spec.workingDirectory().toFile())
                    .redirectErrorStream(true)
                    .start();
            transitionTo(TaskState.RUNNING);
            outputReader = Thread.ofVirtual().name("agentkit-task-output-" + id()).start(
                    this::drainOutput);
            Thread.ofVirtual().name("agentkit-task-wait-" + id()).start(this::awaitProcess);
        } catch (IOException failure) {
            settle(TaskState.FAILED, ToolResult.error(
                    "process start failed: " + safeMessage(failure)));
            throw new IllegalStateException("failed to start background process", failure);
        }
    }

    @Override public TaskId id() { return spec.id(); }

    @Override public TaskScope scope() { return spec.scope(); }

    @Override public TaskState state() { return state.get(); }

    @Override
    public synchronized OutputChunk readSince(OutputCursor cursor) {
        long position = cursor.position();
        if (position > output.length()) {
            throw new IllegalArgumentException("output cursor is beyond current output");
        }
        String content = output.substring(Math.toIntExact(position));
        return new OutputChunk(content, new OutputCursor(output.length()), state());
    }

    @Override public CompletionStage<ToolResult> completion() { return completion; }

    @Override
    public boolean cancel() {
        if (!claimTerminal(TaskState.CANCELLED)) {
            return false;
        }
        ProcessTreeTerminator.terminate(process);
        joinOutputReader();
        completeClaimed(TaskState.CANCELLED, ToolResult.of(
                ToolResultStatus.CANCELLED, withOutput("cancelled")));
        return true;
    }

    private void awaitProcess() {
        try {
            boolean exited = process.waitFor(
                    Math.max(1, spec.timeout().toMillis()), TimeUnit.MILLISECONDS);
            if (!exited) {
                timeOut();
                return;
            }
            joinOutputReader();
            settleFromExit(process.exitValue());
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            cancel();
        } catch (RuntimeException failure) {
            settle(TaskState.FAILED, ToolResult.error(
                    "process error: " + safeMessage(failure)));
        }
    }

    private void timeOut() {
        if (!claimTerminal(TaskState.TIMED_OUT)) {
            return;
        }
        ProcessTreeTerminator.terminate(process);
        joinOutputReader();
        completeClaimed(TaskState.TIMED_OUT, ToolResult.of(
                ToolResultStatus.TIMEOUT,
                withOutput("timeout after " + spec.timeout().toMillis() + "ms")));
    }

    private void settleFromExit(int exitCode) {
        if (exitCode == 0) {
            settle(TaskState.COMPLETED, ToolResult.ok(output()));
        } else {
            settle(TaskState.FAILED, ToolResult.error(withOutput("exit " + exitCode)));
        }
    }

    private boolean settle(TaskState terminal, ToolResult result) {
        if (!claimTerminal(terminal)) {
            return false;
        }
        completeClaimed(terminal, result);
        return true;
    }

    private boolean claimTerminal(TaskState terminal) {
        if (!terminal.terminal()) {
            throw new IllegalArgumentException("task can settle only in a terminal state");
        }
        return terminalClaim.compareAndSet(null, terminal);
    }

    private void completeClaimed(TaskState terminal, ToolResult result) {
        if (terminalClaim.get() != terminal) {
            throw new IllegalStateException("task terminal state was not claimed: " + terminal);
        }
        transitionTo(terminal);
        completion.complete(result);
    }

    private void transitionTo(TaskState target) {
        state.updateAndGet(current -> current.transitionTo(target));
    }

    private void drainOutput() {
        try (var reader = new InputStreamReader(
                process.getInputStream(), StandardCharsets.UTF_8)) {
            char[] buffer = new char[4_096];
            int read;
            while ((read = reader.read(buffer)) >= 0) {
                append(buffer, read);
            }
        } catch (IOException ignored) {
            // Process termination closes the stream; the waiter owns terminal classification.
        }
    }

    private synchronized void append(char[] value, int length) {
        output.append(value, 0, length);
    }

    private synchronized String output() {
        return output.toString();
    }

    private String withOutput(String prefix) {
        String captured = output();
        return captured.isEmpty() ? prefix : prefix + "\n" + captured;
    }

    private void joinOutputReader() {
        Thread reader = outputReader;
        if (reader == null || reader == Thread.currentThread()) {
            return;
        }
        try {
            reader.join(Duration.ofSeconds(1));
            if (reader.isAlive()) {
                reader.interrupt();
                reader.join(Duration.ofMillis(250));
            }
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
        }
    }

    private static String safeMessage(Throwable failure) {
        return failure.getMessage() == null || failure.getMessage().isBlank()
                ? failure.getClass().getSimpleName() : failure.getMessage();
    }
}
