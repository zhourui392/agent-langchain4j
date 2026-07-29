package com.anthropic.agentkit.application;

import com.anthropic.agentkit.domain.agent.AgentBudget;
import com.anthropic.agentkit.domain.agent.AgentRunResult;
import com.anthropic.agentkit.domain.agent.StopReason;
import com.anthropic.agentkit.domain.conversation.CancellationToken;
import com.anthropic.agentkit.domain.conversation.Conversation;
import com.anthropic.agentkit.domain.conversation.SessionId;
import com.anthropic.agentkit.domain.message.AiMessage;
import com.anthropic.agentkit.domain.message.ToolResultMessage;
import com.anthropic.agentkit.domain.message.UserMessage;
import com.anthropic.agentkit.domain.port.LlmClient;
import com.anthropic.agentkit.domain.port.LlmCall;
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
import java.util.Map;

import static com.anthropic.agentkit.testsupport.TestRunContexts.runContext;
import static org.assertj.core.api.Assertions.assertThat;

class AgentExecutorTerminalTest {

    private static final String TERMINAL_NAME = "submit_plan";
    private static final String TERMINAL_SCHEMA = """
            {"type":"object","properties":{"summary":{"type":"string"}},
             "required":["summary"],"additionalProperties":false}
            """;

    @Test
    void terminalToolStopsWithoutSecondLlmCall() {
        StubLlmClient llm = new StubLlmClient().enqueue(terminalCall("terminal-1", validPayload()));
        Conversation conversation = conversation();

        AgentRunResult result = executor(llm, terminalRegistry(new ArrayList<>()))
                .run(conversation, runContext(conversation)).join();

        assertThat(llm.capturedRequests()).hasSize(1);
        assertThat(result.stopReason()).isEqualTo(StopReason.TERMINAL_TOOL);
    }

    @Test
    void terminalResultIsAppendedBeforeRunStops() {
        StubLlmClient llm = new StubLlmClient().enqueue(terminalCall("terminal-1", validPayload()));
        Conversation conversation = conversation();

        AgentRunResult result = executor(llm, terminalRegistry(new ArrayList<>()))
                .run(conversation, runContext(conversation)).join();

        assertThat(conversation.messages()).hasSize(3);
        assertThat(conversation.messages().getLast()).isInstanceOf(ToolResultMessage.class);
        assertThat(((ToolResultMessage) conversation.messages().getLast()).status())
                .isEqualTo(ToolResultStatus.SUCCESS);
        assertThat(result.finalMessage()).isEqualTo(conversation.messages().get(1));
    }

    @Test
    void failedTerminalValidationDoesNotStopRun() {
        StubLlmClient llm = new StubLlmClient()
                .enqueue(terminalCall("invalid", "{\"summary\":42}"))
                .enqueue(terminalCall("corrected", validPayload()));
        Conversation conversation = conversation();

        AgentRunResult result = executor(llm, terminalRegistry(new ArrayList<>()))
                .run(conversation, runContext(conversation)).join();

        assertThat(llm.capturedRequests()).hasSize(2);
        assertThat(result.stopReason()).isEqualTo(StopReason.TERMINAL_TOOL);
        assertThat(toolResults(conversation)).extracting(ToolResultMessage::status)
                .containsExactly(ToolResultStatus.ERROR, ToolResultStatus.SUCCESS);
    }

    @Test
    void mixedTerminalAndNormalToolsAreRejectedWithoutSideEffects() {
        FakeTool write = FakeTool.returning("Write", "changed");
        ToolRegistry tools = terminalRegistry(new ArrayList<>()).register(write);
        StubLlmClient llm = new StubLlmClient().enqueue(AiMessage.of("", List.of(
                request("write-1", "Write", "{}"),
                request("terminal-1", TERMINAL_NAME, validPayload()))));
        Conversation conversation = conversation();

        AgentRunResult result = executor(llm, tools)
                .run(conversation, runContext(conversation)).join();

        assertThat(result.stopReason()).isEqualTo(StopReason.TOOL_PROTOCOL_ERROR);
        assertThat(write.callCount()).isZero();
        assertThat(result.structuredOutput()).isEmpty();
        assertProtocolErrors(conversation, 2);
    }

    @Test
    void multipleTerminalCallsInOneBatchAreRejected() {
        List<Map<String, Object>> accepted = new ArrayList<>();
        StubLlmClient llm = new StubLlmClient().enqueue(AiMessage.of("", List.of(
                request("terminal-1", TERMINAL_NAME, validPayload()),
                request("terminal-2", TERMINAL_NAME, validPayload()))));
        Conversation conversation = conversation();

        AgentRunResult result = executor(llm, terminalRegistry(accepted))
                .run(conversation, runContext(conversation)).join();

        assertThat(result.stopReason()).isEqualTo(StopReason.TOOL_PROTOCOL_ERROR);
        assertThat(accepted).isEmpty();
        assertProtocolErrors(conversation, 2);
    }

    @Test
    void runResultReportsModelCompleted() {
        StubLlmClient llm = new StubLlmClient().enqueue(AiMessage.text("done"));
        Conversation conversation = conversation();

        AgentRunResult result = executor(llm, new ToolRegistry())
                .run(conversation, runContext(conversation)).join();

        assertThat(result.stopReason()).isEqualTo(StopReason.MODEL_COMPLETED);
        assertThat(result.finalMessage().text()).isEqualTo("done");
        assertThat(result.structuredOutput()).isEmpty();
        assertThat(result.consumption().turns()).isOne();
        assertThat(result.consumption().toolCalls()).isZero();
    }

    @Test
    void runResultReportsTerminalPayloadAndUsage() {
        LlmClient llm = (request, handler) -> {
            return LlmCall.start(handler, guarded -> {
                guarded.onUsage(12, 5, 3);
                guarded.onComplete(terminalCall("terminal-1", validPayload()));
            });
        };
        Conversation conversation = conversation();

        AgentRunResult result = executor(llm, terminalRegistry(new ArrayList<>()))
                .run(conversation, runContext(conversation)).join();

        assertThat(result.structuredOutput()).contains(Map.<String, Object>of("summary", "ready"));
        assertThat(result.usage().inputTokens()).isEqualTo(12);
        assertThat(result.usage().outputTokens()).isEqualTo(5);
        assertThat(result.usage().cacheReadInputTokens()).isEqualTo(3);
        assertThat(result.consumption().turns()).isOne();
        assertThat(result.consumption().toolCalls()).isOne();
    }

    @Test
    void runResultReportsBudgetExhaustionWithoutOrphanedToolUse() {
        FakeTool read = FakeTool.readOnlyReturning("Read", "must not run");
        StubLlmClient llm = new StubLlmClient().enqueue(AiMessage.of("", List.of(
                request("read-1", "Read", "{}"),
                request("read-2", "Read", "{}"))));
        Conversation conversation = conversation();

        AgentRunResult result = executor(llm, new ToolRegistry().register(read))
                .run(conversation, runContext(conversation, new CancellationToken(),
                        AgentBudget.of(2, 0, 10_000))).join();

        assertThat(result.stopReason()).isEqualTo(StopReason.BUDGET_EXHAUSTED);
        assertThat(read.callCount()).isZero();
        assertThat(toolResults(conversation)).extracting(ToolResultMessage::status)
                .containsExactly(ToolResultStatus.BUDGET_EXHAUSTED,
                        ToolResultStatus.BUDGET_EXHAUSTED);
    }

    private static AgentExecutor executor(LlmClient llm, ToolRegistry tools) {
        return new AgentExecutor(llm, tools, PermissionService.bypassing());
    }

    private static ToolRegistry terminalRegistry(List<Map<String, Object>> accepted) {
        return new ToolRegistry().register(new StructuredOutputTool(
                TERMINAL_NAME, "Submit plan", TERMINAL_SCHEMA, accepted::add));
    }

    private static Conversation conversation() {
        Conversation conversation = new Conversation(SessionId.fresh());
        conversation.append(UserMessage.of("plan"));
        return conversation;
    }

    private static AiMessage terminalCall(String id, String payload) {
        return AiMessage.of("", List.of(request(id, TERMINAL_NAME, payload)));
    }

    private static ToolUseRequest request(String id, String name, String payload) {
        return new ToolUseRequest(new ToolUseId(id), name, payload);
    }

    private static String validPayload() {
        return "{\"summary\":\"ready\"}";
    }

    private static List<ToolResultMessage> toolResults(Conversation conversation) {
        return conversation.messages().stream()
                .filter(ToolResultMessage.class::isInstance)
                .map(ToolResultMessage.class::cast)
                .toList();
    }

    private static void assertProtocolErrors(Conversation conversation, int count) {
        assertThat(toolResults(conversation)).hasSize(count)
                .allSatisfy(result -> {
                    assertThat(result.status()).isEqualTo(ToolResultStatus.ERROR);
                    assertThat(result.text()).contains("terminal tool must be exclusive");
                });
    }
}
