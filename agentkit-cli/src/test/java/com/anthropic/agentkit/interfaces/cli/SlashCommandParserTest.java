package com.anthropic.agentkit.interfaces.cli;

import com.anthropic.agentkit.application.cli.CliSession;
import com.anthropic.agentkit.application.recovery.RunEventResumer;
import com.anthropic.agentkit.domain.port.RunEventStore;
import com.anthropic.agentkit.interfaces.cli.SlashCommandParser.CommandInvocation;
import com.anthropic.agentkit.interfaces.cli.SlashCommandParser.ParseResult;
import com.anthropic.agentkit.interfaces.cli.SlashCommandParser.UnknownCommand;
import com.anthropic.agentkit.interfaces.cli.SlashCommandParser.UserMessage;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SlashCommandParserTest {

    private final SlashCommandParser parser = new SlashCommandParser()
            .register(command("help"))
            .register(command("clear"));

    @Test
    void parsesKnownCommand() {
        ParseResult result = parser.parse("/help");

        assertThat(result).isInstanceOf(CommandInvocation.class);
        CommandInvocation invocation = (CommandInvocation) result;
        assertThat(invocation.command().name()).isEqualTo("help");
        assertThat(invocation.args()).isEmpty();
    }

    @Test
    void treatsNonSlashAsUserMessage() {
        ParseResult result = parser.parse("hello there");

        assertThat(result).isInstanceOf(UserMessage.class);
        assertThat(((UserMessage) result).text()).isEqualTo("hello there");
    }

    @Test
    void parsesArgs() {
        SlashCommandParser withResume = new SlashCommandParser().register(command("resume"));

        ParseResult result = withResume.parse("/resume abc123");

        assertThat(result).isInstanceOf(CommandInvocation.class);
        CommandInvocation invocation = (CommandInvocation) result;
        assertThat(invocation.command().name()).isEqualTo("resume");
        assertThat(invocation.args()).containsExactly("abc123");
    }

    @Test
    void unknownSlashReturnsError() {
        ParseResult result = parser.parse("/foo");

        assertThat(result).isInstanceOf(UnknownCommand.class);
        assertThat(((UnknownCommand) result).name()).isEqualTo("foo");
    }

    @Test
    void helpCommandLists() {
        CliSession session = new CliSession(new RunEventResumer(RunEventStore.none()));
        SlashCommandParser commands = SlashCommands.standard(session);
        CommandInvocation invocation = (CommandInvocation) commands.parse("/help");
        String output = invocation.command().execute(java.util.List.of());

        assertThat(output).contains("/help", "/clear", "/resume", "exit");
    }

    @Test
    void trimsLeadingWhitespace() {
        ParseResult result = parser.parse("   /clear");
        assertThat(result).isInstanceOf(CommandInvocation.class);
        assertThat(((CommandInvocation) result).command().name()).isEqualTo("clear");
    }

    private static SlashCommand command(String name) {
        return new SlashCommand() {
            @Override public String name() { return name; }
            @Override public String usage() { return name; }
            @Override public String description() { return "Test " + name; }
            @Override public String execute(java.util.List<String> args) { return name; }
        };
    }
}
