package com.anthropic.agentkit.interfaces.engine;

import com.anthropic.agentkit.domain.conversation.CancellationToken;
import com.anthropic.agentkit.domain.message.AiMessage;
import com.anthropic.agentkit.domain.message.UserMessage;
import com.anthropic.agentkit.domain.tool.ExecutionContext;
import com.anthropic.agentkit.domain.tool.Tool;
import com.anthropic.agentkit.domain.tool.ToolArguments;
import com.anthropic.agentkit.domain.tool.ToolRegistry;
import com.anthropic.agentkit.domain.tool.ToolResult;
import com.anthropic.agentkit.domain.tool.ToolUseId;
import com.anthropic.agentkit.domain.tool.ToolUseRequest;
import com.anthropic.agentkit.infrastructure.tools.SubAgentTool;
import com.anthropic.agentkit.testsupport.FakeTool;
import com.anthropic.agentkit.testsupport.StubLlmClient;
import org.junit.jupiter.api.Test;

import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class SubAgentToolTest {

    private final ToolArguments promptArgs = ToolArguments.of(Map.of("prompt", "investigate timeout"));

    @Test
    void returnsChildFinalTextAsToolResult() {
        StubLlmClient llm = new StubLlmClient().enqueue(AiMessage.text("root cause: pool exhausted"));
        SubAgentTool tool = new SubAgentTool(llm, new ToolRegistry());

        ToolResult result = tool.execute(promptArgs, context());

        assertThat(result.success()).isTrue();
        assertThat(result.content()).isEqualTo("root cause: pool exhausted");
    }

    @Test
    void runsChildExecutorWithNarrowedTools() {
        FakeTool grep = FakeTool.readOnlyReturning("Grep", "3 matches");
        StubLlmClient llm = new StubLlmClient()
                .enqueue(new AiMessage("", List.of(new ToolUseRequest(new ToolUseId("c-1"), "Grep", "{}"))))
                .enqueue(AiMessage.text("done"));
        SubAgentTool tool = new SubAgentTool(llm, new ToolRegistry().register(grep));

        ToolResult result = tool.execute(promptArgs, context());

        assertThat(grep.callCount()).isEqualTo(1);
        assertThat(result.content()).isEqualTo("done");
    }

    @Test
    void childToolExecutionUsesIndependentRunIdentity() {
        AtomicReference<ExecutionContext> observed = new AtomicReference<>();
        Tool inspect = contextRecordingTool(observed);
        StubLlmClient llm = new StubLlmClient()
                .enqueue(new AiMessage("", List.of(new ToolUseRequest(
                        new ToolUseId("c-1"), "Inspect", "{}"))))
                .enqueue(AiMessage.text("done"));
        SubAgentTool tool = new SubAgentTool(llm, new ToolRegistry().register(inspect));
        ExecutionContext parent = context();

        tool.execute(promptArgs, parent);

        assertThat(observed.get().runId()).isNotEqualTo(parent.runId());
        assertThat(observed.get().workspaceId()).isEqualTo(parent.workspaceId());
    }

    @Test
    void childDoesNotShareParentConversation() {
        StubLlmClient llm = new StubLlmClient().enqueue(AiMessage.text("ok"));
        SubAgentTool tool = new SubAgentTool(llm, new ToolRegistry());

        tool.execute(promptArgs, context());

        var firstRequest = llm.capturedRequests().get(0);
        assertThat(firstRequest.messages()).hasSize(1);
        assertThat(firstRequest.messages().get(0)).isInstanceOf(UserMessage.class);
        assertThat(((UserMessage) firstRequest.messages().get(0)).text()).isEqualTo("investigate timeout");
    }

    @Test
    void propagatesCancellationToChild() {
        StubLlmClient llm = new StubLlmClient().enqueue(AiMessage.text("never"));
        SubAgentTool tool = new SubAgentTool(llm, new ToolRegistry());
        CancellationToken cancelled = new CancellationToken();
        cancelled.cancel();
        ExecutionContext ctx = ExecutionContext.of(Paths.get(System.getProperty("user.dir")), cancelled);

        ToolResult result = tool.execute(promptArgs, ctx);

        assertThat(result.success()).isFalse();
        assertThat(result.content()).contains("cancel");
        assertThat(llm.capturedRequests()).isEmpty();
    }

    private ExecutionContext context() {
        return ExecutionContext.at(Paths.get(System.getProperty("user.dir")));
    }

    private static Tool contextRecordingTool(AtomicReference<ExecutionContext> observed) {
        return new Tool() {
            @Override public String name() { return "Inspect"; }
            @Override public String description() { return "capture context"; }
            @Override public String inputSchema() { return "{}"; }
            @Override public boolean isReadOnly() { return true; }
            @Override public ToolResult execute(ToolArguments args, ExecutionContext ctx) {
                observed.set(ctx);
                return ToolResult.ok("inspected");
            }
        };
    }
}
