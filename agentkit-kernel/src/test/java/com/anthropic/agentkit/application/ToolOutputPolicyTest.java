package com.anthropic.agentkit.application;

import com.anthropic.agentkit.application.context.ContextPolicy;
import com.anthropic.agentkit.application.tool.LimitedToolOutputPolicy;
import com.anthropic.agentkit.domain.agent.AgentRunResult;
import com.anthropic.agentkit.domain.conversation.Conversation;
import com.anthropic.agentkit.domain.conversation.SessionId;
import com.anthropic.agentkit.domain.message.AiMessage;
import com.anthropic.agentkit.domain.message.ToolResultMessage;
import com.anthropic.agentkit.domain.message.UserMessage;
import com.anthropic.agentkit.domain.tool.ToolRegistry;
import com.anthropic.agentkit.domain.tool.ToolUseId;
import com.anthropic.agentkit.domain.tool.ToolUseRequest;
import com.anthropic.agentkit.testsupport.FakeTool;
import com.anthropic.agentkit.testsupport.StubLlmClient;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.anthropic.agentkit.testsupport.TestRunContexts.runContext;
import static org.assertj.core.api.Assertions.assertThat;

class ToolOutputPolicyTest {

    @Test
    void everyRegisteredToolOutputIsGloballyLimited() {
        StubLlmClient llm = responses();
        ToolRegistry tools = new ToolRegistry()
                .register(FakeTool.readOnlyReturning("First", "a".repeat(40_000)))
                .register(FakeTool.readOnlyReturning("Second", "b".repeat(40_000)));
        Conversation conversation = conversation();

        AgentRunResult ignored = new AgentExecutor(
                llm, tools, PermissionService.bypassing())
                .run(conversation, runContext(conversation)).join();

        List<ToolResultMessage> results = toolResults(llm);
        assertThat(results).hasSize(2).allSatisfy(result -> {
            assertThat(result.text()).hasSizeLessThan(40_000);
            assertThat(result.metadata()).containsEntry(
                    LimitedToolOutputPolicy.DISPOSITION_KEY, "truncated");
        });
    }

    @Test
    void truncatedOutputIncludesStableArtifactReferenceOrExplicitOmission() {
        StubLlmClient llm = responses();
        ToolRegistry tools = new ToolRegistry()
                .register(FakeTool.readOnlyReturning("First", "a".repeat(100)))
                .register(FakeTool.readOnlyReturning("Second", "b".repeat(100)));
        Conversation conversation = conversation();
        AgentExecutor executor = new AgentExecutor(
                llm, tools, PermissionService.bypassing(), ContextPolicy.none(),
                LimitedToolOutputPolicy.of(16));

        executor.run(conversation, runContext(conversation)).join();

        assertThat(toolResults(llm)).allSatisfy(result -> {
            assertThat(result.metadata()).containsEntry(
                    LimitedToolOutputPolicy.ARTIFACT_KEY, "omitted");
            assertThat(result.metadata()).containsKeys(
                    LimitedToolOutputPolicy.ORIGINAL_CHARACTERS_KEY,
                    LimitedToolOutputPolicy.RETAINED_CHARACTERS_KEY);
            assertThat(result.text()).contains("output omitted");
        });
    }

    private static StubLlmClient responses() {
        return new StubLlmClient()
                .enqueue(AiMessage.of("", List.of(
                        request("first-1", "First"), request("second-1", "Second"))))
                .enqueue(AiMessage.text("done"));
    }

    private static ToolUseRequest request(String id, String name) {
        return new ToolUseRequest(new ToolUseId(id), name, "{}");
    }

    private static Conversation conversation() {
        Conversation conversation = new Conversation(SessionId.of("tool-output-policy"));
        conversation.append(UserMessage.of("run both"));
        return conversation;
    }

    private static List<ToolResultMessage> toolResults(StubLlmClient llm) {
        return llm.capturedRequests().get(1).messages().stream()
                .filter(ToolResultMessage.class::isInstance)
                .map(ToolResultMessage.class::cast)
                .toList();
    }
}
