package com.anthropic.cclc.interfaces.cli;

import java.util.List;

public interface SlashCommand {

    String name();

    String execute(List<String> args);
}
