package com.anthropic.agentkit.interfaces.cli;

import com.anthropic.agentkit.application.cli.CliSession;

import java.util.List;
import java.util.Objects;

public final class ClearCommand implements SlashCommand {

    private final CliSession session;

    public ClearCommand(CliSession session) {
        this.session = Objects.requireNonNull(session, "session");
    }

    @Override
    public String name() {
        return "clear";
    }

    @Override
    public String usage() {
        return "clear";
    }

    @Override
    public String description() {
        return "Reset the current conversation";
    }

    @Override
    public String execute(List<String> args) {
        session.clear();
        return "(conversation cleared)";
    }
}
