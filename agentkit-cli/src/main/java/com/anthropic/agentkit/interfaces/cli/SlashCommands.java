package com.anthropic.agentkit.interfaces.cli;

import com.anthropic.agentkit.application.cli.CliSession;

import java.util.Objects;

/** Creates the standard command set from the same registry rendered by /help. */
public final class SlashCommands {

    private SlashCommands() {
    }

    public static SlashCommandParser standard(CliSession session) {
        Objects.requireNonNull(session, "session");
        SlashCommandParser parser = new SlashCommandParser();
        return parser.register(new HelpCommand(parser::commands))
                .register(new ClearCommand(session))
                .register(new ResumeCommand(session));
    }
}
