package com.anthropic.agentkit.interfaces.cli;

import java.util.List;

public final class ClearCommand implements SlashCommand {

    @Override
    public String name() {
        return "clear";
    }

    @Override
    public String execute(List<String> args) {
        return "(conversation cleared)";
    }
}
