package com.anthropic.agentkit.interfaces.cli;

import com.anthropic.agentkit.application.AgentExecutor;
import com.anthropic.agentkit.application.PermissionService;
import com.anthropic.agentkit.domain.agent.AgentBudget;
import com.anthropic.agentkit.domain.agent.AgentRunContext;
import com.anthropic.agentkit.domain.conversation.CancellationToken;
import com.anthropic.agentkit.domain.conversation.Conversation;
import com.anthropic.agentkit.domain.conversation.SessionId;
import com.anthropic.agentkit.domain.message.AiMessage;
import com.anthropic.agentkit.domain.message.UserMessage;
import com.anthropic.agentkit.domain.tool.ToolRegistry;
import com.anthropic.agentkit.domain.tool.ToolUseId;
import com.anthropic.agentkit.domain.tool.ToolUseRequest;
import com.anthropic.agentkit.testsupport.FakeTool;
import com.anthropic.agentkit.testsupport.StubLlmClient;
import com.anthropic.agentkit.testsupport.io.ScriptedTerminalIo;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class RendererEndToEndDemoTest {

    @Test
    void rendersFullTurnWithThinkingToolCallAndFinalAnswer() {
        StubLlmClient stub = new StubLlmClient()
                .enqueue(AiMessage.of("Let me check.",
                        List.of(new ToolUseRequest(new ToolUseId("u1"),
                                "Glob", "{\"pattern\":\"**/*.md\"}"))))
                .enqueue(AiMessage.text("Found 2 markdown files: AGENTS.md, README.md"));

        ToolRegistry tools = new ToolRegistry().register(
                FakeTool.returning("Glob", "AGENTS.md\nREADME.md"));

        ScriptedTerminalIo io = ScriptedTerminalIo.builder().build();
        OutputRenderer renderer = new OutputRenderer(io, false);

        Conversation conv = new Conversation(SessionId.of("demo"));
        conv.append(UserMessage.of("list markdown files"));

        AgentRunContext context = AgentRunContext.create(
                conv.sessionId(), Path.of("."), new CancellationToken(), AgentBudget.unlimited());
        new AgentExecutor(stub, tools, PermissionService.bypassing())
                .run(conv, context, renderer).join();

        String transcript = io.output();
        assertThat(transcript)
                .contains("thinking")
                .contains("Let me check.")
                .contains("> Glob")
                .contains("{\"pattern\":\"**/*.md\"}")
                .contains("[OK]")
                .contains("ms")
                .contains("AGENTS.md")
                .contains("README.md")
                .contains("Found 2 markdown files");
        System.out.println("=== rendered transcript (color off) ===\n" + transcript + "\n=== end ===");
    }
}
