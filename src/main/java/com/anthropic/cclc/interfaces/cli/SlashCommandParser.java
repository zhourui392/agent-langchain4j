package com.anthropic.cclc.interfaces.cli;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class SlashCommandParser {

    private final Map<String, SlashCommand> commands = new LinkedHashMap<>();

    public SlashCommandParser register(SlashCommand command) {
        Objects.requireNonNull(command, "command");
        commands.put(command.name(), command);
        return this;
    }

    public ParseResult parse(String input) {
        Objects.requireNonNull(input, "input");
        String trimmed = input.trim();
        if (!trimmed.startsWith("/")) {
            return ParseResult.userMessage(input);
        }
        String[] tokens = trimmed.substring(1).split("\\s+", -1);
        String name = tokens[0];
        if (name.isEmpty()) {
            return ParseResult.userMessage(input);
        }
        SlashCommand command = commands.get(name);
        if (command == null) {
            return ParseResult.unknownCommand(name);
        }
        List<String> args = new ArrayList<>();
        for (int i = 1; i < tokens.length; i++) {
            if (!tokens[i].isEmpty()) {
                args.add(tokens[i]);
            }
        }
        return ParseResult.command(command, args);
    }

    public sealed interface ParseResult permits CommandInvocation, UserMessage, UnknownCommand {

        static ParseResult command(SlashCommand command, List<String> args) {
            return new CommandInvocation(command, args);
        }

        static ParseResult userMessage(String text) {
            return new UserMessage(text);
        }

        static ParseResult unknownCommand(String name) {
            return new UnknownCommand(name);
        }
    }

    public record CommandInvocation(SlashCommand command, List<String> args) implements ParseResult {

        public CommandInvocation {
            Objects.requireNonNull(command, "command");
            args = List.copyOf(args);
        }
    }

    public record UserMessage(String text) implements ParseResult {

        public UserMessage {
            Objects.requireNonNull(text, "text");
        }
    }

    public record UnknownCommand(String name) implements ParseResult {

        public UnknownCommand {
            Objects.requireNonNull(name, "name");
        }
    }
}
