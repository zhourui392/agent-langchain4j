package com.anthropic.agentkit.interfaces.cli;

import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

public final class HelpCommand implements SlashCommand {

    private final Supplier<List<SlashCommand>> commands;

    public HelpCommand(Supplier<List<SlashCommand>> commands) {
        this.commands = Objects.requireNonNull(commands, "commands");
    }

    @Override
    public String name() {
        return "help";
    }

    @Override
    public String usage() {
        return "help";
    }

    @Override
    public String description() {
        return "Show this help";
    }

    @Override
    public String execute(List<String> args) {
        StringBuilder output = new StringBuilder("Available commands:\n");
        for (SlashCommand command : commands.get()) {
            output.append("  /").append(String.format("%-18s", command.usage()))
                    .append(command.description()).append('\n');
        }
        return output.append("  exit, quit        Leave the REPL").toString();
    }
}
