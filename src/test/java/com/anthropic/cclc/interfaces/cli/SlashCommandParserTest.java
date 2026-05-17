package com.anthropic.cclc.interfaces.cli;

import com.anthropic.cclc.interfaces.cli.SlashCommandParser.CommandInvocation;
import com.anthropic.cclc.interfaces.cli.SlashCommandParser.ParseResult;
import com.anthropic.cclc.interfaces.cli.SlashCommandParser.UnknownCommand;
import com.anthropic.cclc.interfaces.cli.SlashCommandParser.UserMessage;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SlashCommandParserTest {

    private final SlashCommandParser parser = new SlashCommandParser()
            .register(new HelpCommand())
            .register(new ClearCommand());

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
        SlashCommandParser withResume = new SlashCommandParser()
                .register(new HelpCommand())
                .register(new SlashCommand() {
                    @Override public String name() { return "resume"; }
                    @Override public String execute(java.util.List<String> args) {
                        return "resuming " + args;
                    }
                });

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
        String output = new HelpCommand().execute(java.util.List.of());

        assertThat(output).contains("/help", "/clear", "/resume", "exit");
    }

    @Test
    void trimsLeadingWhitespace() {
        ParseResult result = parser.parse("   /clear");
        assertThat(result).isInstanceOf(CommandInvocation.class);
        assertThat(((CommandInvocation) result).command().name()).isEqualTo("clear");
    }
}
