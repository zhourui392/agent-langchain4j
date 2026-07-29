package com.anthropic.agentkit.application;

import com.anthropic.agentkit.domain.agent.AgentBudget;
import com.anthropic.agentkit.domain.agent.AgentRunContext;
import com.anthropic.agentkit.domain.agent.AgentRunResult;
import com.anthropic.agentkit.domain.agent.RunId;
import com.anthropic.agentkit.domain.agent.StopReason;
import com.anthropic.agentkit.domain.agent.WorkspaceId;
import com.anthropic.agentkit.domain.conversation.CancellationToken;
import com.anthropic.agentkit.domain.conversation.Conversation;
import com.anthropic.agentkit.domain.conversation.SessionId;
import com.anthropic.agentkit.domain.message.AiMessage;
import com.anthropic.agentkit.domain.message.ToolResultMessage;
import com.anthropic.agentkit.domain.message.UserMessage;
import com.anthropic.agentkit.domain.permission.Decision;
import com.anthropic.agentkit.domain.permission.PermissionMode;
import com.anthropic.agentkit.domain.port.RunEventStore;
import com.anthropic.agentkit.domain.port.RunSuspensionUnavailableException;
import com.anthropic.agentkit.domain.run.RunEvent;
import com.anthropic.agentkit.domain.suspension.InputRequest;
import com.anthropic.agentkit.domain.suspension.ResumeCommand;
import com.anthropic.agentkit.domain.suspension.ResumeToken;
import com.anthropic.agentkit.domain.suspension.RunSuspension;
import com.anthropic.agentkit.domain.tool.ToolRegistry;
import com.anthropic.agentkit.domain.tool.ToolResultStatus;
import com.anthropic.agentkit.domain.tool.ToolUseId;
import com.anthropic.agentkit.domain.tool.ToolUseRequest;
import com.anthropic.agentkit.infrastructure.memory.FileRunSuspensionStore;
import com.anthropic.agentkit.testsupport.FakeTool;
import com.anthropic.agentkit.testsupport.StubLlmClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentExecutorSuspensionTest {

    @TempDir
    Path tempDir;

    @Test
    void askPermissionSuspendsWithoutExecutingTool() {
        Fixture fixture = fixture("ask-suspends", AiMessage.text("unused"));

        AgentRunResult result = fixture.executor().run(
                fixture.conversation(), fixture.initialContext()).join();

        assertThat(result.stopReason()).isEqualTo(StopReason.WAITING_FOR_APPROVAL);
        assertThat(result.suspension()).containsInstanceOf(
                RunSuspension.WaitingForApproval.class);
        assertThat(result.resumeToken()).isPresent();
        assertThat(fixture.tool().callCount()).isZero();
        assertThat(fixture.prompts()).hasValue(0);
        assertThat(fixture.conversation().messages())
                .containsExactly(UserMessage.of("change it"));
    }

    @Test
    void approvedResumeExecutesOriginalInvocationOnce() {
        Fixture fixture = fixture("approve-once", AiMessage.text("done"));
        AgentRunResult waiting = suspend(fixture);

        AgentRunResult resumed = fixture.executor().resume(
                fixture.conversation(), fixture.resumeContext(),
                ResumeCommand.approve(tokenOf(waiting))).join();

        assertThat(resumed.stopReason()).isEqualTo(StopReason.MODEL_COMPLETED);
        assertThat(fixture.tool().callCount()).isOne();
        assertThat(fixture.conversation().messages()).hasSize(4);
        assertThat(fixture.conversation().messages().get(1)).isInstanceOf(AiMessage.class);
        assertThat(fixture.conversation().messages().get(2)).isInstanceOf(ToolResultMessage.class);
    }

    @Test
    void deniedResumeSettlesOriginalInvocationAsDenied() {
        Fixture fixture = fixture("deny-settles", AiMessage.text("understood"));
        AgentRunResult waiting = suspend(fixture);

        AgentRunResult resumed = fixture.executor().resume(
                fixture.conversation(), fixture.resumeContext(),
                ResumeCommand.deny(tokenOf(waiting))).join();

        assertThat(resumed.stopReason()).isEqualTo(StopReason.MODEL_COMPLETED);
        assertThat(fixture.tool().callCount()).isZero();
        assertThat(fixture.conversation().messages())
                .filteredOn(ToolResultMessage.class::isInstance)
                .singleElement()
                .extracting(message -> ((ToolResultMessage) message).status())
                .isEqualTo(ToolResultStatus.DENIED);
    }

    @Test
    void resumeTokenCannotBeReusedOrCrossWorkspace() {
        Fixture fixture = fixture("scoped-token", AiMessage.text("done"));
        AgentRunResult waiting = suspend(fixture);
        ResumeToken token = tokenOf(waiting);

        assertUnavailable(() -> fixture.executor().resume(
                fixture.conversation(), fixture.crossWorkspaceContext(),
                ResumeCommand.approve(token)).join());
        fixture.executor().resume(fixture.conversation(), fixture.resumeContext(),
                ResumeCommand.approve(token)).join();

        assertThat(fixture.tool().callCount()).isOne();
        assertUnavailable(() -> fixture.executor().resume(
                fixture.conversation(), fixture.secondResumeContext(),
                ResumeCommand.approve(token)).join());
        assertThat(fixture.tool().callCount()).isOne();
    }

    @Test
    void inputAnswerIsAppendedAsNewEvent() {
        MemoryEventStore events = new MemoryEventStore();
        Conversation conversation = conversation("input-answer");
        StubLlmClient llm = new StubLlmClient().enqueue(AiMessage.text("thanks"));
        FileRunSuspensionStore suspensions = suspensionStore("input-answer");
        AgentExecutor executor = executor(
                llm, new ToolRegistry(), PermissionService.bypassing(), events, suspensions);
        AgentRunContext initial = context(conversation.sessionId(), "input-origin", WORKSPACE);

        AgentRunResult waiting = executor.requestInput(
                conversation, initial, InputRequest.of("Which branch?")).join();
        AgentRunResult resumed = executor.resume(
                conversation, context(conversation.sessionId(), "input-resume", WORKSPACE),
                ResumeCommand.answer(tokenOf(waiting), "main")).join();

        assertThat(waiting.stopReason()).isEqualTo(StopReason.WAITING_FOR_INPUT);
        assertThat(resumed.stopReason()).isEqualTo(StopReason.MODEL_COMPLETED);
        assertThat(conversation.messages()).containsSubsequence(
                AiMessage.text("Which branch?"), UserMessage.of("main"), AiMessage.text("thanks"));
        assertThat(events.events).anySatisfy(event -> {
            assertThat(event).isInstanceOf(RunEvent.InputAnswered.class);
            assertThat(((RunEvent.InputAnswered) event).answer().value()).isEqualTo("main");
        });
    }

    private Fixture fixture(String name, AiMessage afterResume) {
        Conversation conversation = conversation(name);
        FakeTool tool = FakeTool.returning("Write", "changed");
        AtomicInteger prompts = new AtomicInteger();
        PermissionService permissions = askingPermissions(prompts);
        StubLlmClient llm = new StubLlmClient()
                .enqueue(toolTurn()).enqueue(afterResume);
        AgentExecutor executor = executor(
                llm, new ToolRegistry().register(tool), permissions,
                new MemoryEventStore(), suspensionStore(name));
        return new Fixture(executor, conversation, tool, prompts,
                context(conversation.sessionId(), name + "-origin", WORKSPACE),
                context(conversation.sessionId(), name + "-resume", WORKSPACE),
                context(conversation.sessionId(), name + "-resume-2", WORKSPACE));
    }

    private FileRunSuspensionStore suspensionStore(String name) {
        return new FileRunSuspensionStore(tempDir.resolve(name));
    }

    private static AgentExecutor executor(
            StubLlmClient llm, ToolRegistry tools, PermissionService permissions,
            RunEventStore events, FileRunSuspensionStore suspensions) {
        return new AgentExecutor(llm, tools, permissions, events, suspensions);
    }

    private static AgentRunResult suspend(Fixture fixture) {
        return fixture.executor().run(
                fixture.conversation(), fixture.initialContext()).join();
    }

    private static ResumeToken tokenOf(AgentRunResult result) {
        return result.resumeToken().orElseThrow();
    }

    private static PermissionService askingPermissions(AtomicInteger prompts) {
        return new PermissionService(
                (invocation, tool, mode) -> Decision.ASK,
                (invocation, tool) -> {
                    prompts.incrementAndGet();
                    return InteractivePrompter.UserPermissionResponse.ALLOW_ONCE;
                },
                PermissionMode.DEFAULT);
    }

    private static Conversation conversation(String sessionId) {
        Conversation conversation = new Conversation(SessionId.of(sessionId));
        conversation.append(UserMessage.of("change it"));
        return conversation;
    }

    private static AiMessage toolTurn() {
        return AiMessage.of("I need to write", List.of(new ToolUseRequest(
                new ToolUseId("write-1"), "Write", "{}")));
    }

    private static AgentRunContext context(
            SessionId sessionId, String runId, WorkspaceId workspaceId) {
        return AgentRunContext.of(
                RunId.of(runId), sessionId, workspaceId, Path.of("."),
                new CancellationToken(), AgentBudget.unlimited());
    }

    private static void assertUnavailable(Runnable operation) {
        assertThatThrownBy(operation::run)
                .hasCauseInstanceOf(RunSuspensionUnavailableException.class);
    }

    private static final WorkspaceId WORKSPACE = WorkspaceId.of("suspension-workspace");

    private record Fixture(
            AgentExecutor executor,
            Conversation conversation,
            FakeTool tool,
            AtomicInteger prompts,
            AgentRunContext initialContext,
            AgentRunContext resumeContext,
            AgentRunContext secondResumeContext) {

        private AgentRunContext crossWorkspaceContext() {
            return context(conversation.sessionId(), "cross-workspace",
                    WorkspaceId.of("other-workspace"));
        }
    }

    private static final class MemoryEventStore implements RunEventStore {
        private final List<RunEvent> events = new ArrayList<>();

        @Override public void append(RunEvent event) { events.add(event); }

        @Override
        public List<RunEvent> load(RunId runId) {
            return events.stream()
                    .filter(event -> event.metadata().runId().equals(runId))
                    .toList();
        }
    }
}
