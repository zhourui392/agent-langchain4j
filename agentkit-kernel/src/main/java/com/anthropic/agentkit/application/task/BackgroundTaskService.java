package com.anthropic.agentkit.application.task;

import com.anthropic.agentkit.domain.port.ArtifactStore;
import com.anthropic.agentkit.domain.port.BackgroundTaskLauncher;
import com.anthropic.agentkit.domain.conversation.CancellationToken;
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
import com.anthropic.agentkit.domain.task.UnknownTaskException;
import com.anthropic.agentkit.domain.tool.ExecutionContext;
import com.anthropic.agentkit.domain.tool.ToolResult;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;

/** Scope-aware registry and completion projection for in-process background tasks. */
public final class BackgroundTaskService implements AutoCloseable {

    private final BackgroundTaskLauncher launcher;
    private final ArtifactStore artifacts;
    private final ArtifactContentPolicy contentPolicy;
    private final BackgroundTaskPolicy policy;
    private final ConcurrentMap<TaskId, ManagedTask> tasks = new ConcurrentHashMap<>();
    private final AtomicBoolean closed = new AtomicBoolean();

    public BackgroundTaskService(
            BackgroundTaskLauncher launcher, ArtifactStore artifacts) {
        this(launcher, artifacts, ArtifactContentPolicy.redactInlineSecrets(),
                BackgroundTaskPolicy.defaults());
    }

    public BackgroundTaskService(
            BackgroundTaskLauncher launcher,
            ArtifactStore artifacts,
            ArtifactContentPolicy contentPolicy,
            BackgroundTaskPolicy policy) {
        this.launcher = Objects.requireNonNull(launcher, "launcher");
        this.artifacts = Objects.requireNonNull(artifacts, "artifacts");
        this.contentPolicy = Objects.requireNonNull(contentPolicy, "contentPolicy");
        this.policy = Objects.requireNonNull(policy, "policy");
    }

    public TaskSnapshot start(BackgroundTaskRequest request, ExecutionContext context) {
        requireOpen();
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(context, "context");
        TaskId id = TaskId.fresh();
        TaskScope scope = TaskScope.from(context);
        Duration timeout = minimum(request.timeout(), context.limits().toolWait());
        TaskLaunchSpec spec = new TaskLaunchSpec(
                id, scope, request.command(), context.cwd(), timeout);
        TaskHandle handle = Objects.requireNonNull(launcher.launch(spec), "task handle");
        requireMatchingHandle(handle, spec);
        ManagedTask task = new ManagedTask(handle, context);
        tasks.put(id, task);
        task.observeCancellation();
        task.observeCompletion();
        return task.snapshot();
    }

    public TaskSnapshot status(TaskId id, ExecutionContext context) {
        return owned(id, context).snapshot();
    }

    public OutputChunk read(
            TaskId id, OutputCursor cursor, ExecutionContext context) {
        Objects.requireNonNull(cursor, "cursor");
        return owned(id, context).read(cursor);
    }

    public boolean stop(TaskId id, ExecutionContext context) {
        return owned(id, context).cancel();
    }

    public Optional<String> readArtifact(
            ArtifactReference reference, ExecutionContext context) {
        Objects.requireNonNull(reference, "reference");
        return artifacts.read(TaskScope.from(context), reference);
    }

    public void close(ExecutionContext context) {
        Objects.requireNonNull(context, "context");
        TaskScope scope = TaskScope.from(context);
        List<ManagedTask> owned = tasks.values().stream()
                .filter(task -> task.scope().equals(scope)).toList();
        owned.forEach(task -> tasks.remove(task.id(), task));
        owned.forEach(ManagedTask::cancel);
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        List<ManagedTask> active = List.copyOf(tasks.values());
        tasks.clear();
        active.forEach(ManagedTask::cancel);
    }

    private ManagedTask owned(TaskId id, ExecutionContext context) {
        requireOpen();
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(context, "context");
        ManagedTask task = tasks.get(id);
        if (task == null || !task.scope().owns(context)) {
            throw new UnknownTaskException(id);
        }
        return task;
    }

    private void requireOpen() {
        if (closed.get()) {
            throw new IllegalStateException("background task service is closed");
        }
    }

    private static void requireMatchingHandle(TaskHandle handle, TaskLaunchSpec spec) {
        if (!spec.id().equals(handle.id()) || !spec.scope().equals(handle.scope())) {
            handle.cancel();
            throw new IllegalStateException("background launcher returned a mismatched handle");
        }
    }

    private static Duration minimum(Duration first, Duration second) {
        return first.compareTo(second) <= 0 ? first : second;
    }

    private final class ManagedTask {
        private final TaskHandle handle;
        private final ExecutionContext context;
        private TaskState projectedState;
        private String preview = "";
        private long outputCharacters;
        private Optional<ArtifactReference> artifact = Optional.empty();
        private CancellationToken.Registration cancellationRegistration =
                CancellationToken.Registration.NO_OP;

        private ManagedTask(TaskHandle handle, ExecutionContext context) {
            this.handle = handle;
            this.context = context;
            this.projectedState = handle.state();
        }

        private void observeCompletion() {
            handle.completion().whenComplete(this::completed);
        }

        private void observeCancellation() {
            cancellationRegistration = context.cancellation().onCancel(handle::cancel);
        }

        private synchronized void completed(ToolResult result, Throwable failure) {
            cancellationRegistration.close();
            if (failure != null) {
                preview = "background task failed: " + failure.getClass().getSimpleName();
                outputCharacters = preview.length();
                projectedState = TaskState.FAILED;
                return;
            }
            String governed = contentPolicy.govern(result.content(), context);
            outputCharacters = result.content().length();
            artifact = storeLargeOutput(governed);
            preview = policy.preview(governed, artifact);
            projectedState = TaskState.from(result.status());
        }

        private Optional<ArtifactReference> storeLargeOutput(String content) {
            if (content.length() <= policy.previewCharacters()) {
                return Optional.empty();
            }
            try {
                return Optional.of(artifacts.write(scope(), content));
            } catch (RuntimeException failure) {
                return Optional.empty();
            }
        }

        private synchronized TaskSnapshot snapshot() {
            if (!projectedState.terminal()) {
                OutputChunk current = handle.readSince(OutputCursor.START);
                projectedState = handle.state();
                outputCharacters = current.next().position();
                preview = policy.preview(current.content(), Optional.empty());
            }
            return new TaskSnapshot(
                    id(), projectedState, preview, outputCharacters, artifact);
        }

        private OutputChunk read(OutputCursor cursor) {
            return handle.readSince(cursor);
        }

        private boolean cancel() {
            return handle.cancel();
        }

        private TaskId id() { return handle.id(); }

        private TaskScope scope() { return handle.scope(); }
    }
}
