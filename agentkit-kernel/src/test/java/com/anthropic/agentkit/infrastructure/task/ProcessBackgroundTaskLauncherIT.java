package com.anthropic.agentkit.infrastructure.task;

import com.anthropic.agentkit.domain.agent.RunId;
import com.anthropic.agentkit.domain.agent.WorkspaceId;
import com.anthropic.agentkit.domain.task.OutputChunk;
import com.anthropic.agentkit.domain.task.OutputCursor;
import com.anthropic.agentkit.domain.task.TaskHandle;
import com.anthropic.agentkit.domain.task.TaskId;
import com.anthropic.agentkit.domain.task.TaskLaunchSpec;
import com.anthropic.agentkit.domain.task.TaskScope;
import com.anthropic.agentkit.domain.task.TaskState;
import com.anthropic.agentkit.domain.tool.ToolResultStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

class ProcessBackgroundTaskLauncherIT {

    @TempDir Path workspace;

    @Test
    void streamsOutputWithMonotonicCursorWithoutDuplication() throws Exception {
        try (ProcessBackgroundTaskLauncher launcher = new ProcessBackgroundTaskLauncher()) {
            TaskHandle handle = launcher.launch(spec("stream", workspace.resolve("unused")));
            assertThat(handle.completion().toCompletableFuture()).isNotDone();

            OutputChunk first = awaitChunk(handle, OutputCursor.START, "first-line");
            assertThat(first.content()).contains("first-line");
            handle.completion().toCompletableFuture().get(5, TimeUnit.SECONDS);
            OutputChunk second = handle.readSince(first.next());

            assertThat(second.content()).contains("second-line").doesNotContain("first-line");
            assertThat(second.next().position()).isGreaterThan(first.next().position());
        }
    }

    @Test
    void cancelTerminatesRootAndDescendantProcessTree() throws Exception {
        Path pids = workspace.resolve("pids.txt");
        try (ProcessBackgroundTaskLauncher launcher = new ProcessBackgroundTaskLauncher()) {
            TaskHandle handle = launcher.launch(spec("tree", pids));
            await(() -> containsTwoPids(pids), Duration.ofSeconds(5));
            List<Long> values = Files.readString(pids).lines().map(Long::parseLong).toList();
            assertThat(values).hasSize(2).allSatisfy(pid -> assertThat(alive(pid)).isTrue());

            assertThat(handle.cancel()).isTrue();
            await(() -> values.stream().noneMatch(ProcessBackgroundTaskLauncherIT::alive),
                    Duration.ofSeconds(5));

            assertThat(handle.completion().toCompletableFuture().get(2, TimeUnit.SECONDS).status())
                    .isEqualTo(ToolResultStatus.CANCELLED);
            assertThat(handle.cancel()).isFalse();
            assertThat(handle.state()).isEqualTo(TaskState.CANCELLED);
        }
    }

    @Test
    void timeoutSettlesOnceAndCannotRegressToCancellation() throws Exception {
        try (ProcessBackgroundTaskLauncher launcher = new ProcessBackgroundTaskLauncher()) {
            TaskHandle handle = launcher.launch(spec(
                    "child", workspace.resolve("unused"), Duration.ofMillis(50)));

            assertThat(handle.completion().toCompletableFuture().get(
                    5, TimeUnit.SECONDS).status()).isEqualTo(ToolResultStatus.TIMEOUT);
            assertThat(handle.state()).isEqualTo(TaskState.TIMED_OUT);
            assertThat(handle.cancel()).isFalse();
            assertThat(handle.state()).isEqualTo(TaskState.TIMED_OUT);
        }
    }

    private TaskLaunchSpec spec(String mode, Path marker) {
        return spec(mode, marker, Duration.ofSeconds(30));
    }

    private TaskLaunchSpec spec(String mode, Path marker, Duration timeout) {
        return new TaskLaunchSpec(TaskId.fresh(), scope(), command(mode, marker),
                workspace, timeout);
    }

    private static List<String> command(String mode, Path marker) {
        String executable = System.getProperty("os.name", "").toLowerCase().contains("win")
                ? "java.exe" : "java";
        Path java = Path.of(System.getProperty("java.home"), "bin", executable);
        return List.of(java.toString(), "-cp", System.getProperty("java.class.path"),
                FakeBackgroundProcess.class.getName(), mode, marker.toString());
    }

    private static TaskScope scope() {
        return new TaskScope(RunId.of("background-run"), WorkspaceId.of("background-workspace"));
    }

    private static OutputChunk awaitChunk(
            TaskHandle handle, OutputCursor cursor, String expected) throws Exception {
        await(() -> handle.readSince(cursor).content().contains(expected), Duration.ofSeconds(5));
        return handle.readSince(cursor);
    }

    private static void await(BooleanSupplier condition, Duration timeout) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertThat(condition.getAsBoolean()).isTrue();
    }

    private static boolean alive(long pid) {
        return ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
    }

    private static boolean containsTwoPids(Path marker) {
        try {
            return Files.exists(marker) && Files.readAllLines(marker).size() == 2;
        } catch (Exception ignored) {
            return false;
        }
    }
}
