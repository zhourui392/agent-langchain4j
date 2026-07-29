package com.anthropic.agentkit;

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
import com.anthropic.agentkit.interfaces.cli.OutputRenderer;
import com.anthropic.agentkit.interfaces.cli.ReplLoop;
import com.anthropic.agentkit.testsupport.StubLlmClient;
import com.anthropic.agentkit.testsupport.io.ScriptedTerminalIo;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class HeadlessPipelineSmokeTest {

    @Test
    void scriptedUserInputDrivesFullTurnAndRendersModelReply() {
        StubLlmClient llm = new StubLlmClient().enqueue(AiMessage.text("Hi! How can I help you today?"));
        ScriptedTerminalIo io = ScriptedTerminalIo.builder()
                .input("hi")
                .build();
        OutputRenderer renderer = new OutputRenderer(io, false);
        AgentExecutor executor = new AgentExecutor(
                llm, new ToolRegistry(), PermissionService.bypassing());
        Conversation conversation = new Conversation(SessionId.fresh());

        ReplLoop repl = new ReplLoop(io, line -> {
            conversation.append(UserMessage.of(line));
            executor.run(conversation, context(conversation), renderer).join();
        });
        repl.run();

        assertThat(io.output()).contains("Hi! How can I help you today?");
        assertThat(llm.capturedRequests()).hasSize(1);
    }

    @Test
    void exitCommandBypassesLlmEntirely() {
        StubLlmClient llm = new StubLlmClient();
        ScriptedTerminalIo io = ScriptedTerminalIo.builder()
                .input("exit")
                .build();
        OutputRenderer renderer = new OutputRenderer(io, false);
        AgentExecutor executor = new AgentExecutor(
                llm, new ToolRegistry(), PermissionService.bypassing());
        Conversation conversation = new Conversation(SessionId.fresh());

        ReplLoop repl = new ReplLoop(io, line -> {
            conversation.append(UserMessage.of(line));
            executor.run(conversation, context(conversation), renderer).join();
        });
        repl.run();

        assertThat(llm.capturedRequests()).isEmpty();
        assertThat(io.output()).isEmpty();
    }

    private static AgentRunContext context(Conversation conversation) {
        return AgentRunContext.create(conversation.sessionId(), Path.of("."),
                new CancellationToken(), AgentBudget.unlimited());
    }
}
