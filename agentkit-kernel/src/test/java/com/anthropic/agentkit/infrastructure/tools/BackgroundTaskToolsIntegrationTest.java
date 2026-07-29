package com.anthropic.agentkit.infrastructure.tools;

import com.anthropic.agentkit.application.AgentExecutor;
import com.anthropic.agentkit.application.PermissionService;
import com.anthropic.agentkit.application.interception.AgentInterceptors;
import com.anthropic.agentkit.application.task.ArtifactContentPolicy;
import com.anthropic.agentkit.application.task.BackgroundTaskCleanupInterceptor;
import com.anthropic.agentkit.application.task.BackgroundTaskPolicy;
import com.anthropic.agentkit.application.task.BackgroundTaskService;
import com.anthropic.agentkit.domain.agent.AgentBudget;
import com.anthropic.agentkit.domain.agent.AgentRunContext;
import com.anthropic.agentkit.domain.conversation.CancellationToken;
import com.anthropic.agentkit.domain.conversation.Conversation;
import com.anthropic.agentkit.domain.conversation.SessionId;
import com.anthropic.agentkit.domain.message.AiMessage;
import com.anthropic.agentkit.domain.message.ToolResultMessage;
import com.anthropic.agentkit.domain.message.UserMessage;
import com.anthropic.agentkit.domain.port.ArtifactStore;
import com.anthropic.agentkit.domain.port.BackgroundTaskLauncher;
import com.anthropic.agentkit.domain.task.ArtifactReference;
import com.anthropic.agentkit.domain.task.BackgroundTaskRequest;
import com.anthropic.agentkit.domain.task.OutputChunk;
import com.anthropic.agentkit.domain.task.OutputCursor;
import com.anthropic.agentkit.domain.task.TaskHandle;
import com.anthropic.agentkit.domain.task.TaskId;
import com.anthropic.agentkit.domain.task.TaskLaunchSpec;
import com.anthropic.agentkit.domain.task.TaskOutputMetadata;
import com.anthropic.agentkit.domain.task.TaskScope;
import com.anthropic.agentkit.domain.task.TaskState;
import com.anthropic.agentkit.domain.tool.ExecutionContext;
import com.anthropic.agentkit.domain.tool.ToolArguments;
import com.anthropic.agentkit.domain.tool.ToolRegistry;
import com.anthropic.agentkit.domain.tool.ToolResult;
import com.anthropic.agentkit.domain.tool.ToolResultStatus;
import com.anthropic.agentkit.domain.tool.ToolUseId;
import com.anthropic.agentkit.domain.tool.ToolUseRequest;
import com.anthropic.agentkit.testsupport.StubLlmClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import static org.assertj.core.api.Assertions.assertThat;

class BackgroundTaskToolsIntegrationTest {

    @TempDir Path workspace;

    @Test
    void completedTaskSettlesOriginalInvocationExactlyOnce() {
        ControlledLauncher launcher = new ControlledLauncher();
        try (BackgroundTaskService service = service(launcher)) {
            StubLlmClient llm = new StubLlmClient()
                    .enqueue(AiMessage.of("starting", List.of(new ToolUseRequest(
                            new ToolUseId("background-1"), "BashBackground",
                            "{\"command\":\"unused\"}"))))
                    .enqueue(AiMessage.text("continuing while it runs"));
            Conversation conversation = conversation("run in background");
            AgentRunContext context = context(conversation.sessionId());

            new AgentExecutor(llm, new ToolRegistry().register(
                    new BackgroundBashTool(service)), PermissionService.bypassing())
                    .run(conversation, context).join();
            List<ToolResultMessage> settled = toolResults(conversation);
            launcher.handle.complete(ToolResult.ok("late completion"));

            assertThat(settled).hasSize(1);
            assertThat(toolResults(conversation)).hasSize(1);
            assertThat(settled.getFirst().metadata())
                    .containsKey(TaskOutputMetadata.TASK_ID_KEY);
        }
    }

    @Test
    void statusReadAndStopAreSeparateGovernedToolCalls() {
        ControlledLauncher launcher = new ControlledLauncher();
        try (BackgroundTaskService service = service(launcher)) {
            ExecutionContext context = context(SessionId.fresh()).executionContext();
            TaskId id = service.start(new BackgroundTaskRequest(
                    "test", List.of("unused"), Duration.ofSeconds(30)), context).id();
            launcher.handle.append("incremental output");
            ToolArguments arguments = ToolArguments.of(Map.of(
                    "task_id", id.value(), "cursor", 0));

            ToolResult status = new TaskStatusTool(service).execute(arguments, context);
            ToolResult output = new TaskReadTool(service).execute(arguments, context);
            ToolResult stopped = new TaskStopTool(service).execute(arguments, context);

            assertThat(status.content()).contains("RUNNING");
            assertThat(output.content()).contains("incremental output").contains("cursor");
            assertThat(stopped.status()).isEqualTo(ToolResultStatus.SUCCESS);
            assertThat(launcher.handle.cancelled).isTrue();
            assertThat(new TaskStatusTool(service).isReadOnly()).isTrue();
            assertThat(new TaskReadTool(service).isReadOnly()).isTrue();
            assertThat(new TaskStopTool(service).isReadOnly()).isFalse();
        }
    }

    @Test
    void runStopCleanupCancelsTasksOwnedByThatRun() {
        ControlledLauncher launcher = new ControlledLauncher();
        try (BackgroundTaskService service = service(launcher)) {
            Conversation conversation = conversation("finish");
            AgentRunContext context = context(conversation.sessionId());
            service.start(new BackgroundTaskRequest(
                    "test", List.of("unused"), Duration.ofSeconds(30)),
                    context.executionContext());

            new AgentExecutor(new StubLlmClient().enqueue(AiMessage.text("done")),
                    new ToolRegistry(), PermissionService.bypassing(),
                    AgentInterceptors.ordered(new BackgroundTaskCleanupInterceptor(service)))
                    .run(conversation, context).join();

            assertThat(launcher.handle.cancelled).isTrue();
        }
    }

    @Test
    void stopToolPreservesAnAlreadyCompletedTaskState() {
        ControlledLauncher launcher = new ControlledLauncher();
        try (BackgroundTaskService service = service(launcher)) {
            ExecutionContext context = context(SessionId.fresh()).executionContext();
            TaskId id = service.start(new BackgroundTaskRequest(
                    "test", List.of("unused"), Duration.ofSeconds(30)), context).id();
            launcher.handle.complete(ToolResult.ok("done"));

            ToolResult stopped = new TaskStopTool(service).execute(
                    ToolArguments.of(Map.of("task_id", id.value())), context);

            assertThat(stopped.content()).contains("COMPLETED", "\"changed\":false");
            assertThat(stopped.metadata()).containsEntry(
                    TaskOutputMetadata.TASK_STATE_KEY, TaskState.COMPLETED.name());
        }
    }

    private BackgroundTaskService service(BackgroundTaskLauncher launcher) {
        return new BackgroundTaskService(
                launcher, new NoArtifactStore(), ArtifactContentPolicy.identity(),
                BackgroundTaskPolicy.of(128));
    }

    private AgentRunContext context(SessionId sessionId) {
        return AgentRunContext.create(
                sessionId, workspace, new CancellationToken(), AgentBudget.unlimited());
    }

    private static Conversation conversation(String request) {
        Conversation conversation = new Conversation(SessionId.fresh());
        conversation.append(UserMessage.of(request));
        return conversation;
    }

    private static List<ToolResultMessage> toolResults(Conversation conversation) {
        return conversation.messages().stream()
                .filter(ToolResultMessage.class::isInstance)
                .map(ToolResultMessage.class::cast).toList();
    }

    private static final class ControlledLauncher implements BackgroundTaskLauncher {
        private ControlledHandle handle;

        @Override
        public TaskHandle launch(TaskLaunchSpec spec) {
            handle = new ControlledHandle(spec.id(), spec.scope());
            return handle;
        }
    }

    private static final class ControlledHandle implements TaskHandle {
        private final TaskId id;
        private final TaskScope scope;
        private final StringBuilder output = new StringBuilder();
        private final CompletableFuture<ToolResult> completion = new CompletableFuture<>();
        private volatile TaskState state = TaskState.RUNNING;
        private boolean cancelled;

        private ControlledHandle(TaskId id, TaskScope scope) {
            this.id = id;
            this.scope = scope;
        }

        private synchronized void append(String value) { output.append(value); }

        private void complete(ToolResult result) {
            state = TaskState.COMPLETED;
            completion.complete(result);
        }

        @Override public TaskId id() { return id; }
        @Override public TaskScope scope() { return scope; }
        @Override public TaskState state() { return state; }

        @Override
        public synchronized OutputChunk readSince(OutputCursor cursor) {
            return new OutputChunk(output.substring(Math.toIntExact(cursor.position())),
                    new OutputCursor(output.length()), state);
        }

        @Override public CompletionStage<ToolResult> completion() { return completion; }

        @Override
        public synchronized boolean cancel() {
            if (state.terminal()) {
                return false;
            }
            cancelled = true;
            state = TaskState.CANCELLED;
            completion.complete(ToolResult.of(ToolResultStatus.CANCELLED, "cancelled"));
            return true;
        }
    }

    private static final class NoArtifactStore implements ArtifactStore {
        @Override
        public ArtifactReference write(TaskScope scope, String content) {
            throw new AssertionError("small test output must not create an artifact");
        }

        @Override
        public Optional<String> read(TaskScope scope, ArtifactReference reference) {
            return Optional.empty();
        }
    }
}
