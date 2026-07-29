package com.anthropic.agentkit.application;

import com.anthropic.agentkit.application.InteractivePrompter.UserPermissionResponse;
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
import com.anthropic.agentkit.domain.message.UserMessage;
import com.anthropic.agentkit.domain.permission.Decision;
import com.anthropic.agentkit.domain.permission.PermissionMode;
import com.anthropic.agentkit.domain.port.ChatRequest;
import com.anthropic.agentkit.domain.port.LlmClient;
import com.anthropic.agentkit.domain.port.LlmCall;
import com.anthropic.agentkit.domain.tool.ExecutionContext;
import com.anthropic.agentkit.domain.tool.Tool;
import com.anthropic.agentkit.domain.tool.ToolArguments;
import com.anthropic.agentkit.domain.tool.ToolRegistry;
import com.anthropic.agentkit.domain.tool.ToolResult;
import com.anthropic.agentkit.domain.tool.ToolUseId;
import com.anthropic.agentkit.domain.tool.ToolUseRequest;
import com.anthropic.agentkit.testsupport.FakeTool;
import com.anthropic.agentkit.testsupport.StubLlmClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentExecutorRunContextTest {

    @Test
    void runUsesProvidedWorkspaceCancellationAndIdentity(@TempDir Path workspace) {
        StubLlmClient llm = toolThenDone("inspect-1", "Inspect", "{}");
        ContextRecordingTool tool = new ContextRecordingTool("Inspect", null);
        AgentExecutor executor = new AgentExecutor(llm, registry(tool), allowAll());
        Conversation conversation = conversation("scope-1");
        CancellationToken cancellation = new CancellationToken();
        AgentRunContext context = context("run-1", "workspace-1", conversation, workspace,
                cancellation, AgentBudget.unlimited());

        executor.run(conversation, context).join();

        ExecutionContext received = tool.contexts().get("default");
        assertThat(received.cwd()).isEqualTo(workspace);
        assertThat(received.cancellation()).isSameAs(cancellation);
        assertThat(received.runId()).isEqualTo(RunId.of("run-1"));
        assertThat(received.workspaceId()).isEqualTo(WorkspaceId.of("workspace-1"));
    }

    @Test
    void listenerReceivesTheExactRunContext(@TempDir Path workspace) {
        StubLlmClient llm = new StubLlmClient().enqueue(AiMessage.text("done"));
        Conversation conversation = conversation("listener-scope");
        AgentRunContext context = context("listener-run", "listener-workspace", conversation,
                workspace, new CancellationToken(), AgentBudget.unlimited());
        AtomicReference<AgentRunContext> observed = new AtomicReference<>();
        AgentEventListener listener = new AgentEventListener() {
            @Override public void onRunStart(AgentRunContext started) { observed.set(started); }
        };

        new AgentExecutor(llm, new ToolRegistry(), allowAll())
                .run(conversation, context, listener).join();

        assertThat(observed).hasValue(context);
    }

    @RepeatedTest(100)
    void sameExecutorRunsTwoWorkspacesConcurrently(@TempDir Path root) {
        CyclicBarrier bothToolsStarted = new CyclicBarrier(2);
        ContextRecordingTool tool = new ContextRecordingTool("Inspect", bothToolsStarted);
        AgentExecutor executor = new AgentExecutor(new PerConversationLlm(), registry(tool), allowAll());
        Conversation first = conversation("first");
        Conversation second = conversation("second");
        AgentRunContext firstContext = context("run-first", "workspace-first", first,
                root.resolve("first"), new CancellationToken(), AgentBudget.unlimited());
        AgentRunContext secondContext = context("run-second", "workspace-second", second,
                root.resolve("second"), new CancellationToken(), AgentBudget.unlimited());

        var firstRun = executor.run(first, firstContext);
        var secondRun = executor.run(second, secondContext);
        firstRun.join();
        secondRun.join();

        assertThat(tool.contexts().get("first").cwd()).isEqualTo(root.resolve("first"));
        assertThat(tool.contexts().get("second").cwd()).isEqualTo(root.resolve("second"));
        assertThat(tool.contexts().get("first").cancellation())
                .isNotSameAs(tool.contexts().get("second").cancellation());
    }

    @Test
    void runBudgetsAreIndependent(@TempDir Path workspace) {
        StubLlmClient llm = new StubLlmClient().enqueue(AiMessage.text("second succeeds"));
        AgentExecutor executor = new AgentExecutor(llm, new ToolRegistry(), allowAll());
        Conversation exhausted = conversation("budget-exhausted");
        Conversation allowed = conversation("budget-allowed");

        AgentRunResult exhaustedResult = executor.run(exhausted, context(
                "run-tight", "workspace", exhausted, workspace, new CancellationToken(),
                AgentBudget.of(0, 0, 0))).join();

        AgentRunResult result = executor.run(allowed, context(
                "run-open", "workspace", allowed, workspace, new CancellationToken(),
                AgentBudget.unlimited())).join();
        assertThat(exhaustedResult.stopReason()).isEqualTo(StopReason.BUDGET_EXHAUSTED);
        assertThat(result.finalMessage().text()).isEqualTo("second succeeds");
    }

    @Test
    void permissionAllowAlwaysDoesNotLeakAcrossRuns(@TempDir Path workspace) {
        StubLlmClient llm = new StubLlmClient()
                .enqueue(toolRequest("tool-1", "Write", "{}"))
                .enqueue(AiMessage.text("first done"))
                .enqueue(toolRequest("tool-2", "Write", "{}"))
                .enqueue(AiMessage.text("second done"));
        FakeTool write = FakeTool.returning("Write", "written");
        AtomicInteger prompts = new AtomicInteger();
        PermissionService permissions = new PermissionService(
                (invocation, tool, mode) -> Decision.ASK,
                (invocation, tool) -> prompts.getAndIncrement() == 0
                        ? UserPermissionResponse.ALLOW_ALWAYS : UserPermissionResponse.DENY,
                PermissionMode.DEFAULT);
        AgentExecutor executor = new AgentExecutor(llm, registry(write), permissions);
        Conversation first = conversation("permission-first");
        Conversation second = conversation("permission-second");

        executor.run(first, context("permission-run-1", "workspace", first, workspace,
                new CancellationToken(), AgentBudget.unlimited())).join();
        executor.run(second, context("permission-run-2", "workspace", second, workspace,
                new CancellationToken(), AgentBudget.unlimited())).join();

        assertThat(prompts).hasValue(2);
        assertThat(write.callCount()).isEqualTo(1);
    }

    private static AgentRunContext context(String runId, String workspaceId,
                                           Conversation conversation, Path workspace,
                                           CancellationToken cancellation, AgentBudget budget) {
        return AgentRunContext.of(RunId.of(runId), conversation.sessionId(),
                WorkspaceId.of(workspaceId), workspace, cancellation, budget);
    }

    private static Conversation conversation(String id) {
        Conversation conversation = new Conversation(SessionId.of(id));
        conversation.append(UserMessage.of(id));
        return conversation;
    }

    private static StubLlmClient toolThenDone(String id, String name, String arguments) {
        return new StubLlmClient().enqueue(toolRequest(id, name, arguments))
                .enqueue(AiMessage.text("done"));
    }

    private static AiMessage toolRequest(String id, String name, String arguments) {
        return AiMessage.of("", List.of(new ToolUseRequest(new ToolUseId(id), name, arguments)));
    }

    private static ToolRegistry registry(Tool tool) {
        return new ToolRegistry().register(tool);
    }

    private static PermissionService allowAll() {
        return new PermissionService((invocation, tool, mode) -> Decision.ALLOW,
                (invocation, tool) -> UserPermissionResponse.DENY, PermissionMode.BYPASS);
    }

    private static final class ContextRecordingTool implements Tool {
        private final String name;
        private final CyclicBarrier barrier;
        private final Map<String, ExecutionContext> contexts = new ConcurrentHashMap<>();

        private ContextRecordingTool(String name, CyclicBarrier barrier) {
            this.name = name;
            this.barrier = barrier;
        }

        Map<String, ExecutionContext> contexts() {
            return Map.copyOf(contexts);
        }

        @Override public String name() { return name; }
        @Override public String description() { return "record execution context"; }
        @Override public String inputSchema() { return "{}"; }
        @Override public boolean isReadOnly() { return true; }

        @Override
        public ToolResult execute(ToolArguments args, ExecutionContext ctx) {
            String key = args.values().containsKey("key") ? args.getString("key") : "default";
            contexts.put(key, ctx);
            awaitBarrier();
            return ToolResult.ok(key);
        }

        private void awaitBarrier() {
            if (barrier == null) {
                return;
            }
            try {
                barrier.await(3, TimeUnit.SECONDS);
            } catch (Exception ex) {
                throw new IllegalStateException("runs did not execute concurrently", ex);
            }
        }
    }

    private static final class PerConversationLlm implements LlmClient {
        private final Map<String, AtomicInteger> turns = new ConcurrentHashMap<>();

        @Override
        public LlmCall streamChat(ChatRequest request, StreamHandler handler) {
            String key = request.messages().stream()
                    .filter(UserMessage.class::isInstance)
                    .map(UserMessage.class::cast)
                    .findFirst()
                    .orElseThrow()
                    .text();
            int turn = turns.computeIfAbsent(key, ignored -> new AtomicInteger()).getAndIncrement();
            AiMessage response = turn == 0
                    ? toolRequest("tool-" + key, "Inspect", "{\"key\":\"" + key + "\"}")
                    : AiMessage.text("done " + key);
            return LlmCall.start(handler, guarded -> guarded.onComplete(response));
        }
    }
}
