package com.anthropic.agentkit.application;

import com.anthropic.agentkit.domain.conversation.CancellationToken;
import com.anthropic.agentkit.domain.conversation.Conversation;
import com.anthropic.agentkit.domain.conversation.SessionId;
import com.anthropic.agentkit.domain.agent.AgentRunResult;
import com.anthropic.agentkit.domain.message.AiMessage;
import com.anthropic.agentkit.domain.message.UserMessage;
import com.anthropic.agentkit.domain.message.ToolResultMessage;
import com.anthropic.agentkit.domain.tool.ToolRegistry;
import com.anthropic.agentkit.domain.tool.ToolUseId;
import com.anthropic.agentkit.domain.tool.ToolUseRequest;
import com.anthropic.agentkit.testsupport.FakeTool;
import com.anthropic.agentkit.testsupport.StubLlmClient;
import org.junit.jupiter.api.Test;

import java.util.List;

import com.anthropic.agentkit.application.InteractivePrompter.UserPermissionResponse;
import com.anthropic.agentkit.domain.permission.PermissionMode;
import com.anthropic.agentkit.infrastructure.permission.DefaultPermissionPolicy;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static com.anthropic.agentkit.testsupport.TestRunContexts.runContext;

class AgentExecutorTest {

    @Test
    void stopsWhenAssistantHasNoToolUse() {
        StubLlmClient stub = new StubLlmClient()
                .enqueue(AiMessage.text("hello world"));
        Conversation conv = new Conversation(SessionId.of("test"));
        conv.append(UserMessage.of("say hi"));

        AgentExecutor executor = new AgentExecutor(stub, new ToolRegistry(), PermissionService.bypassing());
        AgentRunResult result = executor.run(conv, runContext(conv)).join();

        assertThat(stub.capturedRequests()).hasSize(1);
        assertThat(result.finalMessage().text()).isEqualTo("hello world");
        assertThat(conv.messages()).hasSize(2);
        assertThat(conv.messages().get(1)).isInstanceOf(AiMessage.class);
        assertThat(((AiMessage) conv.messages().get(1)).text()).isEqualTo("hello world");
    }

    @Test
    void executesToolAndFeedsResultBackToModel() {
        ToolUseId useId = new ToolUseId("u1");
        StubLlmClient stub = new StubLlmClient()
                .enqueue(AiMessage.of("",
                        List.of(new ToolUseRequest(useId, "Bash", "{\"command\":\"ls\"}"))))
                .enqueue(AiMessage.text("done: file.txt"));

        FakeTool fakeBash = FakeTool.returning("Bash", "file.txt");
        ToolRegistry tools = new ToolRegistry().register(fakeBash);

        Conversation conv = new Conversation(SessionId.of("test"));
        conv.append(UserMessage.of("list files"));

        AgentExecutor executor = new AgentExecutor(stub, tools, PermissionService.bypassing());
        AgentRunResult result = executor.run(conv, runContext(conv)).join();

        assertThat(fakeBash.callCount()).isEqualTo(1);
        assertThat(result.finalMessage().text()).isEqualTo("done: file.txt");
        assertThat(conv.messages()).hasSize(4);
        assertThat(conv.messages().get(2)).isInstanceOf(ToolResultMessage.class);
        ToolResultMessage tr = (ToolResultMessage) conv.messages().get(2);
        assertThat(tr.toolUseId()).isEqualTo(useId);
        assertThat(tr.text()).isEqualTo("file.txt");

        assertThat(stub.capturedRequests()).hasSize(2);
        assertThat(stub.capturedRequests().get(1).messages())
                .anyMatch(m -> m instanceof ToolResultMessage);
    }

    @Test
    void parallelToolsReturnInOriginalOrder() {
        ToolUseId id1 = new ToolUseId("u1");
        ToolUseId id2 = new ToolUseId("u2");
        ToolUseId id3 = new ToolUseId("u3");

        StubLlmClient stub = new StubLlmClient()
                .enqueue(AiMessage.of("", List.of(
                        new ToolUseRequest(id1, "Slow", "{}"),
                        new ToolUseRequest(id2, "Fast", "{}"),
                        new ToolUseRequest(id3, "Medium", "{}"))))
                .enqueue(AiMessage.text("done"));

        ToolRegistry tools = new ToolRegistry()
                .register(FakeTool.withBehavior("Slow", args -> {
                    sleep(150);
                    return com.anthropic.agentkit.domain.tool.ToolResult.ok("slow");
                }))
                .register(FakeTool.withBehavior("Fast", args -> {
                    sleep(20);
                    return com.anthropic.agentkit.domain.tool.ToolResult.ok("fast");
                }))
                .register(FakeTool.withBehavior("Medium", args -> {
                    sleep(80);
                    return com.anthropic.agentkit.domain.tool.ToolResult.ok("medium");
                }));

        Conversation conv = new Conversation(SessionId.of("test"));
        conv.append(UserMessage.of("run three tools"));

        AgentExecutor executor = new AgentExecutor(stub, tools, PermissionService.bypassing());
        long startNs = System.nanoTime();
        executor.run(conv, runContext(conv)).join();
        long elapsedMs = (System.nanoTime() - startNs) / 1_000_000;

        assertThat(elapsedMs).as("parallel dispatch should be faster than sum (=250ms)")
                .isLessThan(220);

        List<ToolResultMessage> results = conv.messages().stream()
                .filter(m -> m instanceof ToolResultMessage)
                .map(m -> (ToolResultMessage) m)
                .toList();

        assertThat(results.stream().map(ToolResultMessage::toolUseId).toList())
                .containsExactly(id1, id2, id3);
        assertThat(results.stream().map(ToolResultMessage::text).toList())
                .containsExactly("slow", "fast", "medium");
    }

    @Test
    void exitsLoopWhenCancelledBeforeFirstTurn() {
        StubLlmClient stub = new StubLlmClient().enqueue(AiMessage.text("never seen"));
        Conversation conv = new Conversation(SessionId.of("test"));
        conv.append(UserMessage.of("hello"));
        CancellationToken cancel = new CancellationToken();
        cancel.cancel();

        AgentExecutor executor = new AgentExecutor(stub, new ToolRegistry(), PermissionService.bypassing());

        assertThatThrownBy(() -> executor.run(conv, runContext(conv, cancel)).join())
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(CancellationException.class);
        assertThat(stub.capturedRequests()).isEmpty();
    }

    @Test
    void exitsLoopWhenCancelledBetweenTurns() {
        ToolUseId useId = new ToolUseId("u1");
        CancellationToken cancel = new CancellationToken();

        StubLlmClient stub = new StubLlmClient()
                .enqueue(AiMessage.of("",
                        List.of(new ToolUseRequest(useId, "CancelOnRun", "{}"))));

        ToolRegistry tools = new ToolRegistry().register(
                FakeTool.withBehavior("CancelOnRun", args -> {
                    cancel.cancel();
                    return com.anthropic.agentkit.domain.tool.ToolResult.ok("tool done");
                }));

        Conversation conv = new Conversation(SessionId.of("test"));
        conv.append(UserMessage.of("trigger cancel"));

        AgentExecutor executor = new AgentExecutor(stub, tools, PermissionService.bypassing());

        assertThatThrownBy(() -> executor.run(conv, runContext(conv, cancel)).join())
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(CancellationException.class);
        assertThat(stub.capturedRequests()).hasSize(1);
    }

    @Test
    void deniedToolReturnsErrorResultWithoutExecution() {
        ToolUseId useId = new ToolUseId("u1");
        StubLlmClient stub = new StubLlmClient()
                .enqueue(AiMessage.of("",
                        List.of(new ToolUseRequest(useId, "Write", "{}"))))
                .enqueue(AiMessage.text("done"));

        AtomicInteger toolCalls = new AtomicInteger(0);
        ToolRegistry tools = new ToolRegistry().register(
                FakeTool.withBehavior("Write", args -> {
                    toolCalls.incrementAndGet();
                    return com.anthropic.agentkit.domain.tool.ToolResult.ok("should not run");
                }));

        PermissionService permissions = new PermissionService(
                new DefaultPermissionPolicy(),
                (inv, tool) -> UserPermissionResponse.DENY,
                PermissionMode.PLAN);

        Conversation conv = new Conversation(SessionId.of("test"));
        conv.append(UserMessage.of("write file"));

        AgentExecutor executor = new AgentExecutor(stub, tools, permissions);
        executor.run(conv, runContext(conv)).join();

        assertThat(toolCalls.get()).isZero();
        assertThat(conv.messages()).filteredOn(m -> m instanceof com.anthropic.agentkit.domain.message.ToolResultMessage)
                .singleElement()
                .satisfies(m -> assertThat(((com.anthropic.agentkit.domain.message.ToolResultMessage) m).text())
                        .contains("permission denied"));
    }

    @Test
    void allowedToolExecutesNormallyWithPermissionService() {
        ToolUseId useId = new ToolUseId("u1");
        StubLlmClient stub = new StubLlmClient()
                .enqueue(AiMessage.of("",
                        List.of(new ToolUseRequest(useId, "Read", "{}"))))
                .enqueue(AiMessage.text("done"));

        ToolRegistry tools = new ToolRegistry().register(FakeTool.readOnlyReturning("Read", "content"));

        PermissionService permissions = new PermissionService(
                new DefaultPermissionPolicy(),
                mock(InteractivePrompter.class),
                PermissionMode.DEFAULT);

        Conversation conv = new Conversation(SessionId.of("test"));
        conv.append(UserMessage.of("read it"));

        AgentExecutor executor = new AgentExecutor(stub, tools, permissions);
        executor.run(conv, runContext(conv)).join();

        assertThat(conv.messages()).filteredOn(m -> m instanceof com.anthropic.agentkit.domain.message.ToolResultMessage)
                .singleElement()
                .satisfies(m -> assertThat(((com.anthropic.agentkit.domain.message.ToolResultMessage) m).text())
                        .isEqualTo("content"));
    }

    @Test
    void askDecisionRoutesThroughPrompter() {
        ToolUseId useId = new ToolUseId("u1");
        StubLlmClient stub = new StubLlmClient()
                .enqueue(AiMessage.of("",
                        List.of(new ToolUseRequest(useId, "Write", "{}"))))
                .enqueue(AiMessage.text("done"));

        ToolRegistry tools = new ToolRegistry().register(FakeTool.returning("Write", "wrote"));

        InteractivePrompter prompter = mock(InteractivePrompter.class);
        when(prompter.ask(any(), any())).thenReturn(UserPermissionResponse.ALLOW_ONCE);

        PermissionService permissions = new PermissionService(
                new DefaultPermissionPolicy(), prompter, PermissionMode.DEFAULT);

        Conversation conv = new Conversation(SessionId.of("test"));
        conv.append(UserMessage.of("write something"));

        AgentExecutor executor = new AgentExecutor(stub, tools, permissions);
        executor.run(conv, runContext(conv)).join();

        verify(prompter, times(1)).ask(any(), any());
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    void requestIncludesConversationHistory() {
        StubLlmClient stub = new StubLlmClient()
                .enqueue(AiMessage.text("ack"));
        Conversation conv = new Conversation(SessionId.of("test"));
        UserMessage user = UserMessage.of("question");
        conv.append(user);

        AgentExecutor executor = new AgentExecutor(stub, new ToolRegistry(), PermissionService.bypassing());
        executor.run(conv, runContext(conv)).join();

        assertThat(stub.capturedRequests().get(0).messages())
                .containsExactly(user);
    }

    @Test
    void requestIncludesProvidedSystemPrompt() {
        StubLlmClient stub = new StubLlmClient().enqueue(AiMessage.text("ack"));
        Conversation conv = new Conversation(SessionId.of("test"));
        conv.append(UserMessage.of("question"));

        AgentExecutor executor = new AgentExecutor(stub, new ToolRegistry(), PermissionService.bypassing());
        executor.run(conv, runContext(conv), AgentEventListener.NO_OP,
                "diagnosis instructions").join();

        assertThat(stub.capturedRequests().get(0).systemPrompt())
                .isEqualTo("diagnosis instructions");
    }

    @Test
    void listenerObservesLlmAndToolEventsInOrder() {
        ToolUseId useId = new ToolUseId("u1");
        StubLlmClient stub = new StubLlmClient()
                .enqueue(AiMessage.of("calling tool",
                        List.of(new ToolUseRequest(useId, "Bash", "{\"command\":\"ls\"}"))))
                .enqueue(AiMessage.text("done"));

        ToolRegistry tools = new ToolRegistry().register(FakeTool.returning("Bash", "out.txt"));
        RecordingAgentEventListener listener = new RecordingAgentEventListener();

        Conversation conv = new Conversation(SessionId.of("test"));
        conv.append(UserMessage.of("ls please"));

        new AgentExecutor(stub, tools, PermissionService.bypassing())
                .run(conv, runContext(conv), listener).join();

        assertThat(listener.events()).containsSubsequence(
                "llmRequestStart",
                "assistantTextDelta:calling tool",
                "toolUseStart:Bash",
                "toolUseEnd:Bash:ok",
                "llmRequestStart",
                "assistantTextDelta:done",
                "turnComplete:done"
        );
    }

    @Test
    void listenerReceivesPermissionDenyAsFailedToolEnd() {
        ToolUseId useId = new ToolUseId("u1");
        StubLlmClient stub = new StubLlmClient()
                .enqueue(AiMessage.of("", List.of(new ToolUseRequest(useId, "Write", "{}"))))
                .enqueue(AiMessage.text("ok"));

        ToolRegistry tools = new ToolRegistry().register(FakeTool.returning("Write", "should not run"));
        PermissionService deny = new PermissionService(
                new DefaultPermissionPolicy(),
                (inv, tool) -> UserPermissionResponse.DENY,
                PermissionMode.PLAN);

        RecordingAgentEventListener listener = new RecordingAgentEventListener();
        Conversation conv = new Conversation(SessionId.of("test"));
        conv.append(UserMessage.of("write something"));

        new AgentExecutor(stub, tools, deny).run(conv, runContext(conv), listener).join();

        assertThat(listener.events()).contains("toolUseEnd:Write:error");
    }

    @Test
    void requestAdvertisesRegisteredToolSpecsInRegistrationOrder() {
        StubLlmClient stub = new StubLlmClient().enqueue(AiMessage.text("ack"));
        ToolRegistry tools = new ToolRegistry()
                .register(FakeTool.returning("Bash", "x"))
                .register(FakeTool.readOnlyReturning("Glob", "y"));

        Conversation conv = new Conversation(SessionId.of("test"));
        conv.append(UserMessage.of("anything"));

        new AgentExecutor(stub, tools, PermissionService.bypassing())
                .run(conv, runContext(conv)).join();

        assertThat(stub.capturedRequests().get(0).tools())
                .extracting(com.anthropic.agentkit.domain.port.ToolSpec::name)
                .containsExactly("Bash", "Glob");
        assertThat(stub.capturedRequests().get(0).tools())
                .extracting(com.anthropic.agentkit.domain.port.ToolSpec::description)
                .containsExactly("fake Bash", "fake Glob");
    }
}
