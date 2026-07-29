package com.anthropic.agentkit.application;

import com.anthropic.agentkit.application.context.ContextDecision;
import com.anthropic.agentkit.application.context.ContextPolicy;
import com.anthropic.agentkit.application.interception.AgentInterceptor;
import com.anthropic.agentkit.application.interception.AgentInterceptors;
import com.anthropic.agentkit.application.interception.CompactionCompleted;
import com.anthropic.agentkit.application.interception.CompactionContext;
import com.anthropic.agentkit.application.interception.CompactionDecision;
import com.anthropic.agentkit.application.interception.LlmCallCompleted;
import com.anthropic.agentkit.application.interception.LlmCallContext;
import com.anthropic.agentkit.application.interception.LlmCallDecision;
import com.anthropic.agentkit.application.interception.RunStopContext;
import com.anthropic.agentkit.application.interception.RunStopDecision;
import com.anthropic.agentkit.application.interception.ToolDispatchContext;
import com.anthropic.agentkit.application.interception.ToolDispatchDecision;
import com.anthropic.agentkit.application.interception.ToolSettled;
import com.anthropic.agentkit.application.tool.ToolOutputPolicy;
import com.anthropic.agentkit.domain.agent.AgentRunResult;
import com.anthropic.agentkit.domain.agent.StopReason;
import com.anthropic.agentkit.domain.conversation.CompactionBoundary;
import com.anthropic.agentkit.domain.conversation.Conversation;
import com.anthropic.agentkit.domain.conversation.SessionId;
import com.anthropic.agentkit.domain.message.AiMessage;
import com.anthropic.agentkit.domain.message.ChatMessage;
import com.anthropic.agentkit.domain.message.ToolResultMessage;
import com.anthropic.agentkit.domain.message.UserMessage;
import com.anthropic.agentkit.domain.port.RunEventStore;
import com.anthropic.agentkit.domain.tool.ToolRegistry;
import com.anthropic.agentkit.domain.tool.ToolResultStatus;
import com.anthropic.agentkit.domain.tool.ToolUseId;
import com.anthropic.agentkit.domain.tool.ToolUseRequest;
import com.anthropic.agentkit.infrastructure.tools.StructuredOutputTool;
import com.anthropic.agentkit.testsupport.FakeTool;
import com.anthropic.agentkit.testsupport.StubLlmClient;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static com.anthropic.agentkit.testsupport.TestRunContexts.runContext;
import static org.assertj.core.api.Assertions.assertThat;

class AgentExecutorInterceptorTest {

    @Test
    void preToolDenialProducesSettledDeniedResult() {
        FakeTool write = FakeTool.returning("Write", "changed");
        StubLlmClient llm = new StubLlmClient()
                .enqueue(toolCall("write-1", "Write", "{}"))
                .enqueue(AiMessage.text("denial observed"));
        AgentInterceptor interceptor = new AgentInterceptor() {
            @Override
            public ToolDispatchDecision beforeToolDispatch(ToolDispatchContext context) {
                return ToolDispatchDecision.deny("host policy denied " + context.toolName());
            }
        };
        Conversation conversation = conversation("write the file");

        AgentRunResult result = executor(llm, registry(write), interceptor)
                .run(conversation, runContext(conversation)).join();

        assertThat(result.stopReason()).isEqualTo(StopReason.MODEL_COMPLETED);
        assertThat(write.callCount()).isZero();
        assertThat(toolResults(conversation)).singleElement().satisfies(toolResult -> {
            assertThat(toolResult.status()).isEqualTo(ToolResultStatus.DENIED);
            assertThat(toolResult.text()).contains("host policy denied Write");
        });
    }

    @Test
    void blockingToolInterceptorFailureSettlesWholeBatchBeforeStop() {
        FakeTool first = FakeTool.returning("First", "must not run");
        FakeTool second = FakeTool.returning("Second", "done");
        StubLlmClient llm = new StubLlmClient().enqueue(toolBatch(
                request("first-1", "First"), request("second-1", "Second")));
        AgentInterceptor interceptor = new AgentInterceptor() {
            @Override
            public ToolDispatchDecision beforeToolDispatch(ToolDispatchContext context) {
                if (context.toolName().equals("First")) {
                    throw new IllegalStateException("tool policy unavailable");
                }
                return ToolDispatchDecision.continueDispatch();
            }
        };
        Conversation conversation = conversation("run both");

        AgentRunResult result = executor(
                llm, new ToolRegistry().register(first).register(second), interceptor)
                .run(conversation, runContext(conversation)).join();

        assertThat(result.stopReason()).isEqualTo(StopReason.INTERCEPTOR_ERROR);
        assertThat(toolResults(conversation)).extracting(ToolResultMessage::status)
                .containsExactly(ToolResultStatus.INTERCEPTOR_ERROR, ToolResultStatus.SUCCESS);
        assertThat(first.callCount()).isZero();
        assertThat(second.callCount()).isOne();
    }

    @Test
    void observerFailureDoesNotFailRun() {
        StubLlmClient llm = new StubLlmClient().enqueue(AiMessage.text("done"));
        List<String> observed = new ArrayList<>();
        AgentInterceptor broken = new AgentInterceptor() {
            @Override
            public void afterLlmCall(LlmCallCompleted event) {
                throw new IllegalStateException("observer boom");
            }
        };
        AgentInterceptor healthy = new AgentInterceptor() {
            @Override
            public void afterLlmCall(LlmCallCompleted event) {
                observed.add(event.message().orElseThrow().text());
            }
        };
        Conversation conversation = conversation("finish");

        AgentRunResult result = executor(llm, new ToolRegistry(), broken, healthy)
                .run(conversation, runContext(conversation)).join();

        assertThat(result.stopReason()).isEqualTo(StopReason.MODEL_COMPLETED);
        assertThat(observed).containsExactly("done");
    }

    @Test
    void toolObserverFailureDoesNotFailRun() {
        FakeTool read = FakeTool.readOnlyReturning("Read", "content");
        StubLlmClient llm = new StubLlmClient()
                .enqueue(toolCall("read-1", "Read", "{}"))
                .enqueue(AiMessage.text("done"));
        AtomicReference<ToolResultStatus> observed = new AtomicReference<>();
        AgentInterceptor broken = new AgentInterceptor() {
            @Override
            public void afterToolSettled(ToolSettled event) {
                throw new IllegalStateException("tool observer boom");
            }
        };
        AgentInterceptor healthy = new AgentInterceptor() {
            @Override
            public void afterToolSettled(ToolSettled event) {
                observed.set(event.result().status());
            }
        };
        Conversation conversation = conversation("read");

        AgentRunResult result = executor(llm, registry(read), broken, healthy)
                .run(conversation, runContext(conversation)).join();

        assertThat(result.stopReason()).isEqualTo(StopReason.MODEL_COMPLETED);
        assertThat(observed).hasValue(ToolResultStatus.SUCCESS);
    }

    @Test
    void blockingInterceptorFailureHasExplicitStopReason() {
        StubLlmClient llm = new StubLlmClient().enqueue(AiMessage.text("must not run"));
        AgentInterceptor interceptor = new AgentInterceptor() {
            @Override
            public LlmCallDecision beforeLlmCall(LlmCallContext context) {
                throw new IllegalStateException("blocking boom");
            }
        };
        Conversation conversation = conversation("blocked");

        AgentRunResult result = executor(llm, new ToolRegistry(), interceptor)
                .run(conversation, runContext(conversation)).join();

        assertThat(result.stopReason()).isEqualTo(StopReason.INTERCEPTOR_ERROR);
        assertThat(result.errorDetail()).hasValueSatisfying(detail ->
                assertThat(detail).contains("beforeLlmCall", "blocking boom"));
        assertThat(llm.capturedRequests()).isEmpty();
    }

    @Test
    void blockingDenialHasExplicitStopReasonWithoutCallingProvider() {
        StubLlmClient llm = new StubLlmClient().enqueue(AiMessage.text("must not run"));
        AgentInterceptor interceptor = new AgentInterceptor() {
            @Override
            public LlmCallDecision beforeLlmCall(LlmCallContext context) {
                return LlmCallDecision.deny("prompt rejected");
            }
        };
        Conversation conversation = conversation("blocked");

        AgentRunResult result = executor(llm, new ToolRegistry(), interceptor)
                .run(conversation, runContext(conversation)).join();

        assertThat(result.stopReason()).isEqualTo(StopReason.INTERCEPTOR_DENIED);
        assertThat(result.errorDetail()).contains("prompt rejected");
        assertThat(llm.capturedRequests()).isEmpty();
    }

    @Test
    void interceptorsRunInDeclaredOrder() {
        List<String> order = new ArrayList<>();
        AgentInterceptor first = orderedInterceptor("first", order);
        AgentInterceptor second = orderedInterceptor("second", order);
        StubLlmClient llm = new StubLlmClient().enqueue(AiMessage.text("done"));
        Conversation conversation = conversation("order");

        AgentRunResult result = executor(llm, new ToolRegistry(), first, second)
                .run(conversation, runContext(conversation)).join();

        assertThat(result.stopReason()).isEqualTo(StopReason.MODEL_COMPLETED);
        assertThat(order).containsExactly(
                "first:before-llm", "second:before-llm",
                "first:after-llm", "second:after-llm");
    }

    @Test
    void terminalValidationCanRejectBeforeStopWithoutLosingPairing() {
        StubLlmClient llm = new StubLlmClient().enqueue(
                toolCall("submit-1", "submit", "{\"summary\":\"unsafe\"}"));
        AgentInterceptor interceptor = new AgentInterceptor() {
            @Override
            public RunStopDecision beforeRunStop(RunStopContext context) {
                return context.proposedResult().stopReason() == StopReason.TERMINAL_TOOL
                        ? RunStopDecision.deny("terminal payload rejected")
                        : RunStopDecision.continueStop();
            }
        };
        Conversation conversation = conversation("submit");

        AgentRunResult result = executor(llm, terminalRegistry(), interceptor)
                .run(conversation, runContext(conversation)).join();

        assertThat(result.stopReason()).isEqualTo(StopReason.INTERCEPTOR_DENIED);
        assertThat(result.structuredOutput()).isEmpty();
        assertThat(result.errorDetail()).contains("terminal payload rejected");
        assertThat(toolResults(conversation)).singleElement()
                .extracting(ToolResultMessage::status).isEqualTo(ToolResultStatus.SUCCESS);
    }

    @Test
    void replaceContextOnlyChangesLlmRequestProjection() {
        StubLlmClient llm = new StubLlmClient().enqueue(AiMessage.text("done"));
        AgentInterceptor redactor = new AgentInterceptor() {
            @Override
            public LlmCallDecision beforeLlmCall(LlmCallContext context) {
                return LlmCallDecision.replaceContext(
                        List.of(UserMessage.of("[redacted]")));
            }
        };
        AtomicReference<List<ChatMessage>> seen = new AtomicReference<>();
        AgentInterceptor auditor = new AgentInterceptor() {
            @Override
            public LlmCallDecision beforeLlmCall(LlmCallContext context) {
                seen.set(context.request().messages());
                return LlmCallDecision.continueCall();
            }
        };
        Conversation conversation = conversation("secret=do-not-send");

        executor(llm, new ToolRegistry(), redactor, auditor)
                .run(conversation, runContext(conversation)).join();

        assertThat(seen.get()).extracting(ChatMessage::text).containsExactly("[redacted]");
        assertThat(llm.capturedRequests().getFirst().messages())
                .extracting(ChatMessage::text).containsExactly("[redacted]");
        assertThat(conversation.messages()).extracting(ChatMessage::text)
                .containsExactly("secret=do-not-send", "done");
    }

    @Test
    void compactionHooksBracketInstalledBoundary() {
        List<String> order = new ArrayList<>();
        AgentInterceptor interceptor = compactionObserver(order);
        ContextPolicy policy = installingCompaction(order);
        StubLlmClient llm = new StubLlmClient().enqueue(AiMessage.text("done"));
        Conversation conversation = conversation("old");
        conversation.append(UserMessage.of("recent"));

        AgentRunResult result = executor(llm, new ToolRegistry(), policy, interceptor)
                .run(conversation, runContext(conversation)).join();

        assertThat(result.stopReason()).isEqualTo(StopReason.MODEL_COMPLETED);
        assertThat(order).containsExactly("before-compaction", "policy", "after-compaction-v1");
        assertThat(conversation.lastCompaction()).isPresent();
    }

    @Test
    void compactionDenialStopsBeforePolicyMutation() {
        AtomicBoolean policyCalled = new AtomicBoolean();
        ContextPolicy policy = (conversation, context) -> {
            policyCalled.set(true);
            return ContextDecision.unchanged();
        };
        AgentInterceptor interceptor = new AgentInterceptor() {
            @Override
            public CompactionDecision beforeCompaction(CompactionContext context) {
                return CompactionDecision.deny("context governance rejected");
            }
        };
        StubLlmClient llm = new StubLlmClient().enqueue(AiMessage.text("must not run"));
        Conversation conversation = conversation("history");

        AgentRunResult result = executor(llm, new ToolRegistry(), policy, interceptor)
                .run(conversation, runContext(conversation)).join();

        assertThat(result.stopReason()).isEqualTo(StopReason.INTERCEPTOR_DENIED);
        assertThat(policyCalled).isFalse();
        assertThat(llm.capturedRequests()).isEmpty();
    }

    private static AgentInterceptor orderedInterceptor(String name, List<String> order) {
        return new AgentInterceptor() {
            @Override
            public LlmCallDecision beforeLlmCall(LlmCallContext context) {
                order.add(name + ":before-llm");
                return LlmCallDecision.continueCall();
            }

            @Override
            public void afterLlmCall(LlmCallCompleted event) {
                order.add(name + ":after-llm");
            }
        };
    }

    private static AgentInterceptor compactionObserver(List<String> order) {
        return new AgentInterceptor() {
            @Override
            public CompactionDecision beforeCompaction(CompactionContext context) {
                order.add("before-compaction");
                return CompactionDecision.continueCompaction();
            }

            @Override
            public void afterCompaction(CompactionCompleted event) {
                order.add("after-compaction-v" + event.boundary().summaryVersion());
            }
        };
    }

    private static ContextPolicy installingCompaction(List<String> order) {
        return (conversation, context) -> {
            order.add("policy");
            List<ChatMessage> before = List.copyOf(conversation.messages());
            CompactionBoundary boundary = new CompactionBoundary(0, 1, 10, 1, "summary");
            conversation.installCompaction(boundary, before.subList(1, before.size()));
            return ContextDecision.compactedContext();
        };
    }

    private static AgentExecutor executor(
            StubLlmClient llm, ToolRegistry tools, AgentInterceptor... interceptors) {
        return executor(llm, tools, ContextPolicy.none(), interceptors);
    }

    private static AgentExecutor executor(
            StubLlmClient llm, ToolRegistry tools, ContextPolicy policy,
            AgentInterceptor... interceptors) {
        return new AgentExecutor(
                llm, tools, PermissionService.bypassing(), policy,
                ToolOutputPolicy.defaultLimited(), RunEventStore.none(),
                AgentInterceptors.ordered(interceptors));
    }

    private static Conversation conversation(String prompt) {
        Conversation conversation = new Conversation(SessionId.fresh());
        conversation.append(UserMessage.of(prompt));
        return conversation;
    }

    private static ToolRegistry registry(FakeTool tool) {
        return new ToolRegistry().register(tool);
    }

    private static ToolRegistry terminalRegistry() {
        return new ToolRegistry().register(new StructuredOutputTool(
                "submit", "Submit output", """
                {"type":"object","properties":{"summary":{"type":"string"}},
                 "required":["summary"],"additionalProperties":false}
                """, ignored -> { }));
    }

    private static AiMessage toolCall(String id, String name, String arguments) {
        return AiMessage.of("", List.of(new ToolUseRequest(
                new ToolUseId(id), name, arguments)));
    }

    private static AiMessage toolBatch(ToolUseRequest... requests) {
        return AiMessage.of("", List.of(requests));
    }

    private static ToolUseRequest request(String id, String name) {
        return new ToolUseRequest(new ToolUseId(id), name, "{}");
    }

    private static List<ToolResultMessage> toolResults(Conversation conversation) {
        return conversation.messages().stream()
                .filter(ToolResultMessage.class::isInstance)
                .map(ToolResultMessage.class::cast)
                .toList();
    }
}
