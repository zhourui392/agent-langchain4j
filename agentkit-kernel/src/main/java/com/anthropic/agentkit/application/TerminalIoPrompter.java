package com.anthropic.agentkit.application;

import com.anthropic.agentkit.application.io.TerminalIo;
import com.anthropic.agentkit.application.io.TerminalIo.PromptAnswer;
import com.anthropic.agentkit.domain.tool.Tool;
import com.anthropic.agentkit.domain.tool.ToolInvocation;

import java.util.Objects;

public final class TerminalIoPrompter implements InteractivePrompter {

    private final TerminalIo terminalIo;

    public TerminalIoPrompter(TerminalIo terminalIo) {
        this.terminalIo = Objects.requireNonNull(terminalIo, "terminalIo");
    }

    @Override
    public UserPermissionResponse ask(ToolInvocation invocation, Tool tool) {
        PromptAnswer answer = terminalIo.promptYesNoAlways("Allow " + tool.name() + "?");
        return switch (answer) {
            case ALLOW_ONCE -> UserPermissionResponse.ALLOW_ONCE;
            case ALLOW_ALWAYS -> UserPermissionResponse.ALLOW_ALWAYS;
            case DENY -> UserPermissionResponse.DENY;
        };
    }
}
