package com.anthropic.agentkit.interfaces.cli;

import com.anthropic.agentkit.application.cli.CliSession;
import com.anthropic.agentkit.application.io.TerminalIo;
import com.anthropic.agentkit.domain.agent.AgentEntryPoint;
import com.anthropic.agentkit.domain.conversation.Conversation;
import com.anthropic.agentkit.domain.message.UserMessage;

import java.util.Objects;

/** Maps parsed terminal input to CLI commands or the selected typed agent entry point. */
public final class CliCommandHandler {

    private final SlashCommandParser parser;
    private final CliSession session;
    private final AgentEntryPoint<CliAgentRequest, CliAgentResult> agent;
    private final TerminalIo terminal;

    public CliCommandHandler(
            SlashCommandParser parser, CliSession session,
            AgentEntryPoint<CliAgentRequest, CliAgentResult> agent, TerminalIo terminal) {
        this.parser = Objects.requireNonNull(parser, "parser");
        this.session = Objects.requireNonNull(session, "session");
        this.agent = Objects.requireNonNull(agent, "agent");
        this.terminal = Objects.requireNonNull(terminal, "terminal");
    }

    public void handle(String input) {
        switch (parser.parse(input)) {
            case SlashCommandParser.UnknownCommand unknown ->
                    terminal.writeError("unknown command: /" + unknown.name());
            case SlashCommandParser.CommandInvocation invocation ->
                    terminal.writeLine(invocation.command().execute(invocation.args()));
            case SlashCommandParser.UserMessage message -> invokeAgent(message.text());
        }
    }

    private void invokeAgent(String text) {
        Conversation conversation = session.activeConversation();
        conversation.append(UserMessage.of(text));
        agent.invoke(new CliAgentRequest(conversation));
    }
}
