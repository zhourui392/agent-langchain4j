package com.anthropic.agentkit.application;

import com.anthropic.agentkit.domain.agent.AgentRunContext;
import com.anthropic.agentkit.domain.conversation.Conversation;
import com.anthropic.agentkit.domain.message.AiMessage;
import com.anthropic.agentkit.domain.message.ToolResultMessage;
import com.anthropic.agentkit.domain.permission.Decision;
import com.anthropic.agentkit.domain.permission.PermissionMode;
import com.anthropic.agentkit.domain.tool.ExecutionContext;
import com.anthropic.agentkit.domain.tool.Tool;
import com.anthropic.agentkit.domain.tool.ToolArguments;
import com.anthropic.agentkit.domain.tool.ToolRegistry;
import com.anthropic.agentkit.domain.tool.ToolResult;
import com.anthropic.agentkit.domain.tool.ToolResultStatus;
import com.anthropic.agentkit.domain.tool.ToolUseId;
import com.anthropic.agentkit.domain.tool.ToolUseRequest;
import com.anthropic.agentkit.testsupport.FakeTool;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CancellationException;

import static org.assertj.core.api.Assertions.assertThat;

class ParallelToolDispatcherSettlementTest {

    @Test
    void unknownToolReturnsOrderedErrorResult() {
        ToolRegistry tools = new ToolRegistry()
                .register(FakeTool.readOnlyReturning("Known", "known-result"));
        DispatcherFixture fixture = fixture(tools, allowAll());
        AiMessage assistant = batch(
                request("one", "Missing", "{}"),
                request("two", "Known", "{}"));

        List<ToolResultMessage> results = fixture.dispatcher().dispatch(assistant);

        assertOutcomes(results,
                List.of("one", "two"),
                List.of(ToolResultStatus.UNKNOWN_TOOL, ToolResultStatus.SUCCESS));
    }

    @Test
    void invalidArgumentsReturnOrderedErrorResult() {
        DispatcherFixture fixture = fixture(
                new ToolRegistry().register(FakeTool.readOnlyReturning("Read", "ok")), allowAll());
        AiMessage assistant = batch(
                request("bad", "Read", "{not-json"),
                request("good", "Read", "{}"));

        List<ToolResultMessage> results = fixture.dispatcher().dispatch(assistant);

        assertOutcomes(results,
                List.of("bad", "good"),
                List.of(ToolResultStatus.INVALID_ARGUMENTS, ToolResultStatus.SUCCESS));
    }

    @Test
    void permissionFailureSettlesInvocationAsError() {
        PermissionService brokenPermissions = new PermissionService(
                (invocation, tool, mode) -> { throw new IllegalStateException("policy unavailable"); },
                (invocation, tool) -> { throw new AssertionError("must not prompt"); },
                PermissionMode.DEFAULT);
        DispatcherFixture fixture = fixture(
                new ToolRegistry().register(FakeTool.readOnlyReturning("Read", "ok")),
                brokenPermissions);

        ToolResultMessage result = fixture.dispatcher()
                .dispatch(batch(request("permission", "Read", "{}"))).getFirst();

        assertThat(result.status()).isEqualTo(ToolResultStatus.ERROR);
        assertThat(result.metadata()).containsEntry("stage", "permission");
    }

    @Test
    void listenerFailureCannotOrphanToolInvocation() {
        DispatcherFixture fixture = fixture(
                new ToolRegistry().register(FakeTool.readOnlyReturning("Read", "ok")), allowAll());
        AiMessage assistant = batch(request("listener", "Read", "{}"));
        Conversation conversation = new Conversation(fixture.context().sessionId());
        conversation.append(assistant);
        AgentEventListener brokenListener = new AgentEventListener() {
            @Override public void onToolUseStart(ToolUseRequest request) {
                throw new IllegalStateException("start observer failed");
            }
            @Override public void onToolUseEnd(ToolUseRequest request, ToolResult result, long durationMs) {
                throw new IllegalStateException("end observer failed");
            }
        };

        fixture.dispatcher().dispatch(assistant, brokenListener).forEach(conversation::append);

        assertThat(conversation.messages()).hasSize(2);
        assertThat(((ToolResultMessage) conversation.messages().get(1)).status())
                .isEqualTo(ToolResultStatus.SUCCESS);
    }

    @RepeatedTest(100)
    void cancelledParallelBatchSettlesEveryAcceptedRequest() {
        Tool cancelled = throwingTool("Cancelled", new CancellationException("cancelled"));
        ToolRegistry tools = new ToolRegistry()
                .register(cancelled)
                .register(FakeTool.readOnlyReturning("Ok", "ok"));
        DispatcherFixture fixture = fixture(tools, allowAll());
        AiMessage assistant = batch(
                request("first", "Cancelled", "{}"),
                request("second", "Ok", "{}"),
                request("third", "Cancelled", "{}"));

        List<ToolResultMessage> results = fixture.dispatcher().dispatch(assistant);

        assertOutcomes(results,
                List.of("first", "second", "third"),
                List.of(ToolResultStatus.CANCELLED,
                        ToolResultStatus.SUCCESS, ToolResultStatus.CANCELLED));
        assertThat(results).allSatisfy(result -> assertThat(result.status().isTerminal()).isTrue());
    }

    private static DispatcherFixture fixture(ToolRegistry tools, PermissionService permissions) {
        AgentRunContext context = AgentRunContext.at(Path.of("."));
        return new DispatcherFixture(
                context,
                new ParallelToolDispatcher(
                        tools, context.executionContext(), permissions));
    }

    private static PermissionService allowAll() {
        return new PermissionService(
                (invocation, tool, mode) -> Decision.ALLOW,
                (invocation, tool) -> { throw new AssertionError("must not prompt"); },
                PermissionMode.BYPASS);
    }

    private static AiMessage batch(ToolUseRequest... requests) {
        return AiMessage.of("", List.of(requests));
    }

    private static ToolUseRequest request(String id, String tool, String arguments) {
        return new ToolUseRequest(new ToolUseId(id), tool, arguments);
    }

    private static Tool throwingTool(String name, RuntimeException failure) {
        return new Tool() {
            @Override public String name() { return name; }
            @Override public String description() { return "throws"; }
            @Override public String inputSchema() { return "{}"; }
            @Override public boolean isReadOnly() { return true; }
            @Override public ToolResult execute(ToolArguments args, ExecutionContext ctx) { throw failure; }
        };
    }

    private static void assertOutcomes(List<ToolResultMessage> results,
                                       List<String> expectedIds,
                                       List<ToolResultStatus> expectedStatuses) {
        assertThat(results).extracting(result -> result.toolUseId().value())
                .containsExactlyElementsOf(expectedIds);
        assertThat(results).extracting(ToolResultMessage::status)
                .containsExactlyElementsOf(expectedStatuses);
    }

    private record DispatcherFixture(
            AgentRunContext context,
            ParallelToolDispatcher dispatcher) {
    }
}
