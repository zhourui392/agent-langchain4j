package com.anthropic.agentkit.interfaces.cli;

import java.util.List;

public interface SlashCommand {

    String name();

    String usage();

    String description();

    String execute(List<String> args);
}
