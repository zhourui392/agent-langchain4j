package com.anthropic.cclc.application;

import com.anthropic.cclc.application.io.TerminalIo;
import com.anthropic.cclc.application.io.TerminalIo.PromptAnswer;
import com.anthropic.cclc.domain.tool.Tool;
import com.anthropic.cclc.domain.tool.ToolInvocation;

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
