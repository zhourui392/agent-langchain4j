package com.anthropic.agentkit.interfaces.cli;

import java.util.List;

public final class HelpCommand implements SlashCommand {

    @Override
    public String name() {
        return "help";
    }

    @Override
    public String execute(List<String> args) {
        return """
                Available commands:
                  /help            Show this help
                  /clear           Reset the current conversation
                  /resume <id>     Resume a previous session by id
                  exit, quit       Leave the REPL""";
    }
}
