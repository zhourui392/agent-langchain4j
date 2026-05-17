package com.anthropic.cclc.interfaces.cli;

import com.anthropic.cclc.application.AgentExecutor;
import com.anthropic.cclc.domain.conversation.CancellationToken;
import com.anthropic.cclc.domain.conversation.Conversation;
import com.anthropic.cclc.domain.conversation.SessionId;
import com.anthropic.cclc.domain.message.AiMessage;
import com.anthropic.cclc.domain.message.UserMessage;
import com.anthropic.cclc.domain.tool.ToolRegistry;
import com.anthropic.cclc.domain.tool.ToolUseId;
import com.anthropic.cclc.domain.tool.ToolUseRequest;
import com.anthropic.cclc.testsupport.FakeTool;
import com.anthropic.cclc.testsupport.StubLlmClient;
import com.anthropic.cclc.testsupport.io.ScriptedTerminalIo;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RendererEndToEndDemoTest {

    @Test
    void rendersFullTurnWithThinkingToolCallAndFinalAnswer() {
        StubLlmClient stub = new StubLlmClient()
                .enqueue(AiMessage.of("Let me check.",
                        List.of(new ToolUseRequest(new ToolUseId("u1"),
                                "Glob", "{\"pattern\":\"**/*.md\"}"))))
                .enqueue(AiMessage.text("Found 2 markdown files: CLAUDE.md, README.md"));

        ToolRegistry tools = new ToolRegistry().register(
                FakeTool.returning("Glob", "CLAUDE.md\nREADME.md"));

        ScriptedTerminalIo io = ScriptedTerminalIo.builder().build();
        OutputRenderer renderer = new OutputRenderer(io, false);

        Conversation conv = new Conversation(SessionId.of("demo"));
        conv.append(UserMessage.of("list markdown files"));

        new AgentExecutor(stub, tools).run(conv, new CancellationToken(), renderer).join();

        String transcript = io.output();
        assertThat(transcript)
                .contains("thinking")
                .contains("Let me check.")
                .contains("⏵ Glob")
                .contains("{\"pattern\":\"**/*.md\"}")
                .contains("ms")
                .contains("CLAUDE.md")
                .contains("README.md")
                .contains("Found 2 markdown files");
        System.out.println("=== rendered transcript (color off) ===\n" + transcript + "\n=== end ===");
    }
}
