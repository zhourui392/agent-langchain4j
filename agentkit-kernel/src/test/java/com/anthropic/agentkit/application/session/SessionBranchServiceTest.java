package com.anthropic.agentkit.application.session;

import com.anthropic.agentkit.domain.agent.AgentBudget;
import com.anthropic.agentkit.domain.agent.RunId;
import com.anthropic.agentkit.domain.agent.WorkspaceId;
import com.anthropic.agentkit.domain.checkpoint.CheckpointId;
import com.anthropic.agentkit.domain.checkpoint.FileCheckpointMetadata;
import com.anthropic.agentkit.domain.conversation.CancellationToken;
import com.anthropic.agentkit.domain.conversation.SessionId;
import com.anthropic.agentkit.domain.message.AiMessage;
import com.anthropic.agentkit.domain.message.UserMessage;
import com.anthropic.agentkit.domain.run.RunEvent;
import com.anthropic.agentkit.domain.run.RunEventMetadata;
import com.anthropic.agentkit.domain.session.BranchOrigin;
import com.anthropic.agentkit.domain.session.RewindMode;
import com.anthropic.agentkit.domain.session.RewindResult;
import com.anthropic.agentkit.domain.session.RunEventPointer;
import com.anthropic.agentkit.domain.session.SessionBranch;
import com.anthropic.agentkit.domain.session.SessionBranchScope;
import com.anthropic.agentkit.domain.session.SessionBranchUnavailableException;
import com.anthropic.agentkit.domain.tool.ExecutionContext;
import com.anthropic.agentkit.domain.tool.ToolArguments;
import com.anthropic.agentkit.domain.tool.ToolResult;
import com.anthropic.agentkit.domain.tool.ToolSideEffect;
import com.anthropic.agentkit.domain.tool.ToolUseId;
import com.anthropic.agentkit.domain.tool.ToolUseRequest;
import com.anthropic.agentkit.infrastructure.checkpoint.FileSystemCheckpointProvider;
import com.anthropic.agentkit.infrastructure.memory.FileRunEventStore;
import com.anthropic.agentkit.infrastructure.memory.FileSessionBranchStore;
import com.anthropic.agentkit.infrastructure.tools.FileReadTool;
import com.anthropic.agentkit.infrastructure.tools.FileWriteTool;
import com.anthropic.agentkit.infrastructure.tools.support.FileStateCache;
import com.anthropic.agentkit.infrastructure.tools.support.WorkspaceBoundary;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SessionBranchServiceTest {

    private static final RunId RUN = RunId.of("branch-run");
    private static final SessionId SESSION = SessionId.of("branch-session");
    private static final WorkspaceId WORKSPACE = WorkspaceId.of("branch-workspace");
    private static final SessionBranchScope SCOPE =
            new SessionBranchScope(SESSION, WORKSPACE);

    @TempDir Path tempDir;

    @Test
    void forkReferencesImmutableParentSequence() {
        Fixture fixture = fixture("fork");
        seedConversation(fixture.events());
        SessionBranch parent = fixture.service().createRoot(SCOPE, pointer(2));

        SessionBranch fork = fixture.service().fork(SCOPE, parent.id(), pointer(1));
        fixture.events().append(new RunEvent.LlmCallStarted(metadata(3), 1));

        assertThat(fork.parentPoint()).get().satisfies(point -> {
            assertThat(point.branchId()).isEqualTo(parent.id());
            assertThat(point.event()).isEqualTo(pointer(1));
        });
        assertThat(fixture.service().load(SCOPE, fork.id()).parentPoint())
                .isEqualTo(fork.parentPoint());
    }

    @Test
    void rewindCreatesBranchWithoutDeletingHistory() throws IOException {
        Fixture fixture = fixture("rewind");
        seedConversation(fixture.events());
        SessionBranch parent = fixture.service().createRoot(SCOPE, pointer(2));
        Path parentLog = fixture.branches().pathFor(parent.id());
        byte[] originalHistory = Files.readAllBytes(parentLog);

        RewindResult rewind = fixture.service().rewind(
                SCOPE, parent.id(), pointer(1), RewindMode.CONVERSATION_ONLY);

        assertThat(rewind.branch().id()).isNotEqualTo(parent.id());
        assertThat(rewind.branch().origin()).isEqualTo(BranchOrigin.REWIND);
        assertThat(rewind.conversation().messages()).containsExactly(UserMessage.of("start"));
        assertThat(Files.readAllBytes(parentLog)).isEqualTo(originalHistory);
        assertThat(fixture.branches().load(parent.id())).isNotEmpty();
    }

    @Test
    void fileCheckpointRestoresKernelManagedEdit() throws IOException {
        Fixture fixture = fixture("file-checkpoint");
        Path file = fixture.workspace().resolve("note.txt");
        Files.writeString(file, "before");
        ToolUseRequest request = new ToolUseRequest(
                new ToolUseId("write-1"), "Write", "{\"path\":\"note.txt\"}");
        ToolResult result = executeWrite(fixture, file, "after");
        CheckpointId checkpoint = CheckpointId.of(
                result.metadata().get(FileCheckpointMetadata.CHECKPOINT_ID_KEY));
        appendFileWriteEvents(fixture.events(), request, result, checkpoint);
        SessionBranch parent = fixture.service().createRoot(SCOPE, pointer(5));

        RewindResult rewind = fixture.service().rewind(
                SCOPE, parent.id(), pointer(1), RewindMode.CONVERSATION_AND_FILES);

        assertThat(Files.readString(file)).isEqualTo("before");
        assertThat(rewind.restoredCheckpoints()).containsExactly(checkpoint);
        assertThat(rewind.residualSideEffects()).isEmpty();
    }

    @Test
    void rewindReportsNonReversibleSideEffects() {
        Fixture fixture = fixture("external-effect");
        ToolUseId invocation = new ToolUseId("bash-1");
        appendExternalEffectEvents(fixture.events(), invocation);
        SessionBranch parent = fixture.service().createRoot(SCOPE, pointer(5));

        RewindResult rewind = fixture.service().rewind(
                SCOPE, parent.id(), pointer(1), RewindMode.CONVERSATION_AND_FILES);

        assertThat(rewind.residualSideEffects()).singleElement().satisfies(residual -> {
            assertThat(residual.toolUseId()).isEqualTo(invocation);
            assertThat(residual.toolName()).isEqualTo("Bash");
            assertThat(residual.event()).isEqualTo(pointer(4));
        });
        assertThat(rewind.restoredCheckpoints()).isEmpty();
    }

    @Test
    void fileCheckpointsAreRestoredInReverseSideEffectOrder() throws IOException {
        Fixture fixture = fixture("checkpoint-order");
        Path file = fixture.workspace().resolve("ordered.txt");
        Files.writeString(file, "zero");
        ToolResult first = executeWrite(fixture, file, "one");
        ToolResult second = executeWrite(fixture, file, "two");
        CheckpointId firstId = checkpointOf(first);
        CheckpointId secondId = checkpointOf(second);
        appendTwoFileWriteEvents(fixture.events(), first, firstId, second, secondId);
        SessionBranch parent = fixture.service().createRoot(SCOPE, pointer(8));

        RewindResult rewind = fixture.service().rewind(
                SCOPE, parent.id(), pointer(1), RewindMode.CONVERSATION_AND_FILES);

        assertThat(Files.readString(file)).isEqualTo("zero");
        assertThat(rewind.restoredCheckpoints()).containsExactly(secondId, firstId);
        assertThat(rewind.unrestoredCheckpoints()).isEmpty();
    }

    @Test
    void conversationOnlyRewindReportsUnrestoredFileCheckpoint() throws IOException {
        Fixture fixture = fixture("conversation-only");
        Path file = fixture.workspace().resolve("note.txt");
        Files.writeString(file, "before");
        ToolUseRequest request = writeRequest("write-only");
        ToolResult result = executeWrite(fixture, file, "after");
        CheckpointId checkpoint = checkpointOf(result);
        appendFileWriteEvents(fixture.events(), request, result, checkpoint);
        SessionBranch parent = fixture.service().createRoot(SCOPE, pointer(5));

        RewindResult rewind = fixture.service().rewind(
                SCOPE, parent.id(), pointer(1), RewindMode.CONVERSATION_ONLY);

        assertThat(Files.readString(file)).isEqualTo("after");
        assertThat(rewind.restoredCheckpoints()).isEmpty();
        assertThat(rewind.unrestoredCheckpoints()).containsExactly(checkpoint);
    }

    @Test
    void failedFileRestoreReturnsResidualWithoutDeletingBranch() throws IOException {
        Fixture fixture = fixture("restore-failure");
        Path file = fixture.workspace().resolve("note.txt");
        Files.writeString(file, "before");
        ToolUseRequest request = writeRequest("write-failure");
        ToolResult result = executeWrite(fixture, file, "after");
        CheckpointId checkpoint = checkpointOf(result);
        appendFileWriteEvents(fixture.events(), request, result, checkpoint);
        SessionBranch parent = fixture.service().createRoot(SCOPE, pointer(5));
        Files.delete(fixture.checkpoints().pathFor(checkpoint));

        RewindResult rewind = fixture.service().rewind(
                SCOPE, parent.id(), pointer(1), RewindMode.CONVERSATION_AND_FILES);

        assertThat(rewind.unrestoredCheckpoints()).containsExactly(checkpoint);
        assertThat(rewind.residualSideEffects()).singleElement()
                .extracting(residual -> residual.toolName())
                .isEqualTo("FileCheckpoint");
        assertThat(fixture.branches().load(rewind.branch().id())).isNotEmpty();
    }

    @Test
    void branchCannotCrossWorkspaceBoundary() {
        Fixture fixture = fixture("scope");
        seedConversation(fixture.events());
        SessionBranch parent = fixture.service().createRoot(SCOPE, pointer(2));
        SessionBranchScope other = new SessionBranchScope(
                SESSION, WorkspaceId.of("other-workspace"));

        assertThatThrownBy(() -> fixture.service().fork(
                other, parent.id(), pointer(1)))
                .isInstanceOf(SessionBranchUnavailableException.class);
        assertThat(fixture.branches().load(parent.id())).hasSize(1);
    }

    private Fixture fixture(String name) {
        Path root = tempDir.resolve(name);
        Path workspace = root.resolve("workspace");
        try {
            Files.createDirectories(workspace);
        } catch (IOException failure) {
            throw new AssertionError(failure);
        }
        FileRunEventStore events = new FileRunEventStore(root.resolve("events"));
        FileSessionBranchStore branches = new FileSessionBranchStore(root.resolve("branches"));
        FileSystemCheckpointProvider checkpoints =
                new FileSystemCheckpointProvider(root.resolve("checkpoints"));
        return new Fixture(workspace, events, branches, checkpoints,
                new SessionBranchService(events, branches, checkpoints));
    }

    private ToolResult executeWrite(Fixture fixture, Path file, String content) {
        FileStateCache cache = new FileStateCache();
        ExecutionContext context = executionContext(fixture.workspace());
        new FileReadTool(cache).execute(
                ToolArguments.of(Map.of("path", file.toString())), context);
        return new FileWriteTool(cache, new WorkspaceBoundary(), fixture.checkpoints())
                .execute(ToolArguments.of(Map.of(
                        "path", file.toString(), "content", content)), context);
    }

    private static void seedConversation(FileRunEventStore events) {
        events.append(started(1));
        events.append(new RunEvent.AssistantTurnReceived(
                metadata(2), AiMessage.text("answer")));
    }

    private static void appendFileWriteEvents(
            FileRunEventStore events, ToolUseRequest request,
            ToolResult result, CheckpointId checkpoint) {
        events.append(started(1));
        events.append(new RunEvent.AssistantTurnReceived(
                metadata(2), AiMessage.of("write", List.of(request))));
        events.append(new RunEvent.ToolInvocationStarted(metadata(3), request.id()));
        events.append(new RunEvent.ToolSideEffectObserved(
                metadata(4), request.id(), new ToolSideEffect.CheckpointedFile(checkpoint)));
        events.append(new RunEvent.ToolInvocationSettled(metadata(5), request.id(), result));
    }

    private static void appendTwoFileWriteEvents(
            FileRunEventStore events,
            ToolResult first, CheckpointId firstId,
            ToolResult second, CheckpointId secondId) {
        ToolUseRequest firstRequest = writeRequest("write-1");
        ToolUseRequest secondRequest = writeRequest("write-2");
        events.append(started(1));
        events.append(new RunEvent.AssistantTurnReceived(
                metadata(2), AiMessage.of("write twice", List.of(firstRequest, secondRequest))));
        appendFileEffect(events, 3, firstRequest, first, firstId);
        appendFileEffect(events, 6, secondRequest, second, secondId);
    }

    private static void appendFileEffect(
            FileRunEventStore events, long sequence,
            ToolUseRequest request, ToolResult result, CheckpointId checkpoint) {
        events.append(new RunEvent.ToolInvocationStarted(metadata(sequence), request.id()));
        events.append(new RunEvent.ToolSideEffectObserved(
                metadata(sequence + 1), request.id(),
                new ToolSideEffect.CheckpointedFile(checkpoint)));
        events.append(new RunEvent.ToolInvocationSettled(
                metadata(sequence + 2), request.id(), result));
    }

    private static void appendExternalEffectEvents(
            FileRunEventStore events, ToolUseId invocation) {
        ToolUseRequest request = new ToolUseRequest(invocation, "Bash", "{\"command\":\"deploy\"}");
        events.append(started(1));
        events.append(new RunEvent.AssistantTurnReceived(
                metadata(2), AiMessage.of("run", List.of(request))));
        events.append(new RunEvent.ToolInvocationStarted(metadata(3), invocation));
        events.append(new RunEvent.ToolSideEffectObserved(metadata(4), invocation,
                new ToolSideEffect.NonReversible(
                        "Bash", "external process side effects cannot be restored")));
        events.append(new RunEvent.ToolInvocationSettled(
                metadata(5), invocation, ToolResult.ok("deployed")));
    }

    private static ExecutionContext executionContext(Path workspace) {
        return ExecutionContext.of(
                RUN, SESSION, WORKSPACE, workspace,
                new CancellationToken(), AgentBudget.unlimited());
    }

    private static CheckpointId checkpointOf(ToolResult result) {
        return CheckpointId.of(
                result.metadata().get(FileCheckpointMetadata.CHECKPOINT_ID_KEY));
    }

    private static ToolUseRequest writeRequest(String id) {
        return new ToolUseRequest(
                new ToolUseId(id), "Write", "{\"path\":\"note.txt\"}");
    }

    private static RunEvent.RunStarted started(long sequence) {
        return new RunEvent.RunStarted(
                metadata(sequence), List.of(UserMessage.of("start")), Optional.empty());
    }

    private static RunEventPointer pointer(long sequence) {
        return new RunEventPointer(RUN, sequence);
    }

    private static RunEventMetadata metadata(long sequence) {
        return new RunEventMetadata(
                RunEvent.CURRENT_SCHEMA_VERSION, RUN, SESSION, WORKSPACE, sequence,
                Instant.parse("2026-07-29T17:00:00Z").plusSeconds(sequence));
    }

    private record Fixture(
            Path workspace,
            FileRunEventStore events,
            FileSessionBranchStore branches,
            FileSystemCheckpointProvider checkpoints,
            SessionBranchService service) {
    }
}
