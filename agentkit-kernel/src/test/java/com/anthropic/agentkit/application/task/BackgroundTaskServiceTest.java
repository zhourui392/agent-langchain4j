package com.anthropic.agentkit.application.task;

import com.anthropic.agentkit.domain.agent.AgentBudget;
import com.anthropic.agentkit.domain.agent.AgentRunLimits;
import com.anthropic.agentkit.domain.agent.RunDeadline;
import com.anthropic.agentkit.domain.agent.RunId;
import com.anthropic.agentkit.domain.agent.WorkspaceId;
import com.anthropic.agentkit.domain.conversation.CancellationToken;
import com.anthropic.agentkit.domain.port.ArtifactStore;
import com.anthropic.agentkit.domain.port.ArtifactStoreException;
import com.anthropic.agentkit.domain.port.BackgroundTaskLauncher;
import com.anthropic.agentkit.domain.port.SecretProvider;
import com.anthropic.agentkit.domain.task.ArtifactId;
import com.anthropic.agentkit.domain.task.ArtifactReference;
import com.anthropic.agentkit.domain.task.BackgroundTaskRequest;
import com.anthropic.agentkit.domain.task.OutputChunk;
import com.anthropic.agentkit.domain.task.OutputCursor;
import com.anthropic.agentkit.domain.task.TaskHandle;
import com.anthropic.agentkit.domain.task.TaskId;
import com.anthropic.agentkit.domain.task.TaskLaunchSpec;
import com.anthropic.agentkit.domain.task.TaskScope;
import com.anthropic.agentkit.domain.task.TaskSnapshot;
import com.anthropic.agentkit.domain.task.TaskState;
import com.anthropic.agentkit.domain.task.TaskStopResult;
import com.anthropic.agentkit.domain.task.UnknownTaskException;
import com.anthropic.agentkit.domain.tool.ExecutionContext;
import com.anthropic.agentkit.domain.tool.ToolResult;
import com.anthropic.agentkit.domain.tool.ToolResultStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BackgroundTaskServiceTest {

    @TempDir Path workspace;

    @Test
    void backgroundTaskReturnsHandleBeforeProcessCompletes() {
        ControlledLauncher launcher = new ControlledLauncher();
        try (BackgroundTaskService service = service(launcher, new MemoryArtifactStore(), 128)) {
            TaskSnapshot started = service.start(request(), context("run-a", "workspace-a"));

            assertThat(started.id()).isEqualTo(launcher.handle.id());
            assertThat(started.state()).isEqualTo(TaskState.RUNNING);
            assertThat(launcher.handle.completion().toCompletableFuture()).isNotDone();
        }
    }

    @Test
    void readsOutputIncrementallyWithoutDuplication() {
        ControlledLauncher launcher = new ControlledLauncher();
        try (BackgroundTaskService service = service(launcher, new MemoryArtifactStore(), 128)) {
            ExecutionContext context = context("run-a", "workspace-a");
            TaskId id = service.start(request(), context).id();
            launcher.handle.append("first");
            OutputChunk first = service.read(id, OutputCursor.START, context);
            launcher.handle.append("-second");
            OutputChunk second = service.read(id, first.next(), context);

            assertThat(first.content()).isEqualTo("first");
            assertThat(second.content()).isEqualTo("-second");
            assertThat(second.next().position()).isEqualTo(12);
        }
    }

    @Test
    void cannotReadOrStopTaskFromAnotherWorkspace() {
        ControlledLauncher launcher = new ControlledLauncher();
        try (BackgroundTaskService service = service(launcher, new MemoryArtifactStore(), 128)) {
            TaskId id = service.start(request(), context("run-a", "workspace-a")).id();
            ExecutionContext intruder = context("run-a", "workspace-b");

            assertThatThrownBy(() -> service.read(id, OutputCursor.START, intruder))
                    .isInstanceOf(UnknownTaskException.class);
            assertThatThrownBy(() -> service.stop(id, intruder))
                    .isInstanceOf(UnknownTaskException.class);
            assertThatThrownBy(() -> service.status(
                    id, context("run-b", "workspace-a")))
                    .isInstanceOf(UnknownTaskException.class);
            assertThat(launcher.handle.cancelled).isFalse();
        }
    }

    @Test
    void largeOutputStoresRedactedArtifactAndReturnsBoundedPreview() {
        ControlledLauncher launcher = new ControlledLauncher();
        MemoryArtifactStore artifacts = new MemoryArtifactStore();
        ArtifactContentPolicy redactor = (content, ignored) ->
                content.replace("token=secret-value", "token=[REDACTED]");
        try (BackgroundTaskService service = new BackgroundTaskService(
                launcher, artifacts, redactor, BackgroundTaskPolicy.of(128))) {
            ExecutionContext context = context("run-a", "workspace-a");
            TaskId id = service.start(request(), context).id();
            launcher.handle.complete(ToolResult.ok(
                    "token=secret-value\n" + "x".repeat(40_000)), TaskState.COMPLETED);

            TaskSnapshot completed = service.status(id, context);
            assertThat(completed.preview()).hasSizeLessThan(1_000);
            assertThat(completed.artifact()).isPresent();
            assertThat(completed.artifact().orElseThrow().uri().toString())
                    .startsWith("artifact://");
            assertThat(artifacts.onlyContent()).contains("x".repeat(1_000))
                    .doesNotContain("secret-value");
        }
    }

    @Test
    void closingRunScopeCancelsOwnedProcessOnly() {
        ControlledLauncher first = new ControlledLauncher();
        ControlledLauncher second = new ControlledLauncher();
        SequencedLauncher launcher = new SequencedLauncher(first, second);
        try (BackgroundTaskService service = service(launcher, new MemoryArtifactStore(), 128)) {
            ExecutionContext owner = context("run-a", "workspace-a");
            service.start(request(), owner);
            service.start(request(), context("run-b", "workspace-a"));

            service.close(owner);

            assertThat(first.handle.cancelled).isTrue();
            assertThat(second.handle.cancelled).isFalse();
        }
    }

    @Test
    void taskTimeoutCannotExceedRunLimitAndRunCancellationPropagates() {
        ControlledLauncher launcher = new ControlledLauncher();
        CancellationToken cancellation = new CancellationToken();
        ExecutionContext base = ExecutionContext.of(
                RunId.of("run-a"), WorkspaceId.of("workspace-a"), workspace,
                cancellation, AgentBudget.unlimited());
        AgentRunLimits limits = new AgentRunLimits(
                RunDeadline.unlimited(), Duration.ofSeconds(1), Duration.ofMillis(25));
        ExecutionContext limited = ExecutionContext.of(
                base.runId(), base.workspaceId(), base.cwd(), cancellation, base.budget(),
                SecretProvider.none(), limits, base.budgetState());

        try (BackgroundTaskService service = service(
                launcher, new MemoryArtifactStore(), 128)) {
            service.start(request(), limited);
            cancellation.cancel();

            assertThat(launcher.spec.timeout()).isEqualTo(Duration.ofMillis(25));
            assertThat(launcher.handle.cancelled).isTrue();
        }
    }

    @Test
    void terminalCompletionReleasesCancellationAndCleanupIsIdempotent() {
        ControlledLauncher launcher = new ControlledLauncher();
        CancellationToken cancellation = new CancellationToken();
        ExecutionContext context = context("run-a", "workspace-a", cancellation);
        try (BackgroundTaskService service = service(
                launcher, new MemoryArtifactStore(), 128)) {
            service.start(request(), context);
            launcher.handle.complete(ToolResult.ok("done"), TaskState.COMPLETED);

            cancellation.cancel();
            assertThat(launcher.handle.cancelAttempts).isZero();
            service.close(context);
            service.close(context);

            assertThat(launcher.handle.cancellations).isZero();
            assertThat(launcher.handle.cancelAttempts).isEqualTo(1);
            assertThat(launcher.handle.state()).isEqualTo(TaskState.COMPLETED);
        }
    }

    @Test
    void stoppingCompletedTaskReportsActualStateWithoutChangingIt() {
        ControlledLauncher launcher = new ControlledLauncher();
        try (BackgroundTaskService service = service(
                launcher, new MemoryArtifactStore(), 128)) {
            ExecutionContext context = context("run-a", "workspace-a");
            TaskId id = service.start(request(), context).id();
            launcher.handle.complete(ToolResult.ok("done"), TaskState.COMPLETED);

            TaskStopResult stopped = service.stop(id, context);

            assertThat(stopped.changed()).isFalse();
            assertThat(stopped.snapshot().state()).isEqualTo(TaskState.COMPLETED);
            assertThat(launcher.handle.state()).isEqualTo(TaskState.COMPLETED);
        }
    }

    @Test
    void closedRunScopeRejectsNewTasksWithoutAffectingAnotherScope() {
        ControlledLauncher launcher = new ControlledLauncher();
        try (BackgroundTaskService service = service(
                launcher, new MemoryArtifactStore(), 128)) {
            ExecutionContext closed = context("run-a", "workspace-a");
            service.close(closed);

            assertThatThrownBy(() -> service.start(request(), closed))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("scope is closed");
            assertThat(service.start(
                    request(), context("run-b", "workspace-a")).state())
                    .isEqualTo(TaskState.RUNNING);
        }
    }

    @Test
    void artifactFailureKeepsTerminalStateAndReturnsExplicitOmission() {
        ControlledLauncher launcher = new ControlledLauncher();
        ArtifactStore failing = new FailingArtifactStore();
        try (BackgroundTaskService service = service(launcher, failing, 16)) {
            ExecutionContext context = context("run-a", "workspace-a");
            TaskId id = service.start(request(), context).id();
            launcher.handle.complete(
                    ToolResult.ok("x".repeat(100)), TaskState.COMPLETED);

            TaskSnapshot snapshot = service.status(id, context);

            assertThat(snapshot.state()).isEqualTo(TaskState.COMPLETED);
            assertThat(snapshot.artifact()).isEmpty();
            assertThat(snapshot.preview()).contains("artifact unavailable")
                    .hasSizeLessThan(120);
        }
    }

    @Test
    void contentGovernanceFailureNeverExposesRawOutput() {
        ControlledLauncher launcher = new ControlledLauncher();
        ArtifactContentPolicy failing = (content, context) -> {
            throw new IllegalStateException("policy unavailable");
        };
        try (BackgroundTaskService service = new BackgroundTaskService(
                launcher, new MemoryArtifactStore(), failing,
                BackgroundTaskPolicy.of(16))) {
            ExecutionContext context = context("run-a", "workspace-a");
            TaskId id = service.start(request(), context).id();
            launcher.handle.complete(
                    ToolResult.ok("token=raw-secret"), TaskState.COMPLETED);

            TaskSnapshot snapshot = service.status(id, context);

            assertThat(snapshot.state()).isEqualTo(TaskState.FAILED);
            assertThat(snapshot.preview()).contains("output unavailable")
                    .doesNotContain("raw-secret");
            assertThat(snapshot.artifact()).isEmpty();
        }
    }

    @Test
    void activePreviewUsesTheSameContentGovernanceAsCompletion() {
        ControlledLauncher launcher = new ControlledLauncher();
        ArtifactContentPolicy redactor = (content, context) ->
                content.replace("token=raw-secret", "token=[REDACTED]");
        try (BackgroundTaskService service = new BackgroundTaskService(
                launcher, new MemoryArtifactStore(), redactor,
                BackgroundTaskPolicy.of(32))) {
            ExecutionContext context = context("run-a", "workspace-a");
            TaskId id = service.start(request(), context).id();
            launcher.handle.append("token=raw-secret");

            TaskSnapshot snapshot = service.status(id, context);

            assertThat(snapshot.state()).isEqualTo(TaskState.RUNNING);
            assertThat(snapshot.preview()).contains("token=[REDACTED]")
                    .doesNotContain("raw-secret");
        }
    }

    private BackgroundTaskService service(
            BackgroundTaskLauncher launcher, ArtifactStore artifacts, int preview) {
        return new BackgroundTaskService(
                launcher, artifacts, ArtifactContentPolicy.identity(),
                BackgroundTaskPolicy.of(preview));
    }

    private BackgroundTaskRequest request() {
        return new BackgroundTaskRequest(
                "test command", List.of("unused"), Duration.ofSeconds(30));
    }

    private ExecutionContext context(String runId, String workspaceId) {
        return context(runId, workspaceId, new CancellationToken());
    }

    private ExecutionContext context(
            String runId, String workspaceId, CancellationToken cancellation) {
        return ExecutionContext.of(
                RunId.of(runId), WorkspaceId.of(workspaceId), workspace,
                cancellation, AgentBudget.unlimited());
    }

    private static final class ControlledLauncher implements BackgroundTaskLauncher {
        private ControlledHandle handle;
        private TaskLaunchSpec spec;

        @Override
        public TaskHandle launch(TaskLaunchSpec spec) {
            this.spec = spec;
            handle = new ControlledHandle(spec.id(), spec.scope());
            return handle;
        }
    }

    private static final class SequencedLauncher implements BackgroundTaskLauncher {
        private final List<ControlledLauncher> launchers;
        private int next;

        private SequencedLauncher(ControlledLauncher... launchers) {
            this.launchers = List.of(launchers);
        }

        @Override
        public TaskHandle launch(TaskLaunchSpec spec) {
            return launchers.get(next++).launch(spec);
        }
    }

    private static final class ControlledHandle implements TaskHandle {
        private final TaskId id;
        private final TaskScope scope;
        private final StringBuilder output = new StringBuilder();
        private final CompletableFuture<ToolResult> completion = new CompletableFuture<>();
        private volatile TaskState state = TaskState.RUNNING;
        private volatile boolean cancelled;
        private int cancelAttempts;
        private int cancellations;

        private ControlledHandle(TaskId id, TaskScope scope) {
            this.id = id;
            this.scope = scope;
        }

        private synchronized void append(String content) {
            output.append(content);
        }

        private void complete(ToolResult result, TaskState terminal) {
            state = terminal;
            completion.complete(result);
        }

        @Override public TaskId id() { return id; }
        @Override public TaskScope scope() { return scope; }
        @Override public TaskState state() { return state; }

        @Override
        public synchronized OutputChunk readSince(OutputCursor cursor) {
            String content = output.substring(Math.toIntExact(cursor.position()));
            return new OutputChunk(content, new OutputCursor(output.length()), state);
        }

        @Override public CompletionStage<ToolResult> completion() { return completion; }

        @Override
        public synchronized boolean cancel() {
            cancelAttempts++;
            if (state.terminal()) {
                return false;
            }
            cancellations++;
            cancelled = true;
            state = TaskState.CANCELLED;
            completion.complete(ToolResult.of(ToolResultStatus.CANCELLED, "cancelled"));
            return true;
        }
    }

    private static final class MemoryArtifactStore implements ArtifactStore {
        private final Map<ArtifactId, Stored> values = new java.util.LinkedHashMap<>();

        @Override
        public ArtifactReference write(TaskScope scope, String content) {
            ArtifactId id = ArtifactId.fresh();
            ArtifactReference reference = new ArtifactReference(
                    id, URI.create("artifact://" + id.value()), content.length(),
                    Instant.now().plusSeconds(300));
            values.put(id, new Stored(scope, content));
            return reference;
        }

        @Override
        public Optional<String> read(TaskScope scope, ArtifactReference reference) {
            Stored stored = values.get(reference.id());
            return stored != null && stored.scope().equals(scope)
                    ? Optional.of(stored.content()) : Optional.empty();
        }

        private String onlyContent() {
            return values.values().iterator().next().content();
        }

        private record Stored(TaskScope scope, String content) { }
    }

    private static final class FailingArtifactStore implements ArtifactStore {
        @Override
        public ArtifactReference write(TaskScope scope, String content) {
            throw new ArtifactStoreException("artifact unavailable", null);
        }

        @Override
        public Optional<String> read(TaskScope scope, ArtifactReference reference) {
            return Optional.empty();
        }
    }
}
