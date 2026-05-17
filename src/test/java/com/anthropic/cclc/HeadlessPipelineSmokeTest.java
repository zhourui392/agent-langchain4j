package com.anthropic.cclc;

import com.anthropic.cclc.application.AgentExecutor;
import com.anthropic.cclc.domain.conversation.CancellationToken;
import com.anthropic.cclc.domain.conversation.Conversation;
import com.anthropic.cclc.domain.conversation.SessionId;
import com.anthropic.cclc.domain.message.AiMessage;
import com.anthropic.cclc.domain.message.UserMessage;
import com.anthropic.cclc.domain.tool.ToolRegistry;
import com.anthropic.cclc.interfaces.cli.OutputRenderer;
import com.anthropic.cclc.interfaces.cli.ReplLoop;
import com.anthropic.cclc.testsupport.StubLlmClient;
import com.anthropic.cclc.testsupport.io.ScriptedTerminalIo;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class HeadlessPipelineSmokeTest {

    @Test
    void scriptedUserInputDrivesFullTurnAndRendersModelReply() {
        StubLlmClient llm = new StubLlmClient().enqueue(AiMessage.text("Hi! How can I help you today?"));
        ScriptedTerminalIo io = ScriptedTerminalIo.builder()
                .input("hi")
                .build();
        OutputRenderer renderer = new OutputRenderer(io, false);
        AgentExecutor executor = new AgentExecutor(llm, new ToolRegistry());
        Conversation conversation = new Conversation(SessionId.fresh());

        ReplLoop repl = new ReplLoop(io, line -> {
            conversation.append(UserMessage.of(line));
            executor.run(conversation, new CancellationToken(), renderer).join();
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
        AgentExecutor executor = new AgentExecutor(llm, new ToolRegistry());
        Conversation conversation = new Conversation(SessionId.fresh());

        ReplLoop repl = new ReplLoop(io, line -> {
            conversation.append(UserMessage.of(line));
            executor.run(conversation, new CancellationToken(), renderer).join();
        });
        repl.run();

        assertThat(llm.capturedRequests()).isEmpty();
        assertThat(io.output()).isEmpty();
    }
}
