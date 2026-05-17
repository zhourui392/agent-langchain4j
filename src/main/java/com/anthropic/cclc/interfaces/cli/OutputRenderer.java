package com.anthropic.cclc.interfaces.cli;

import com.anthropic.cclc.application.io.TerminalIo;
import com.anthropic.cclc.domain.message.AiMessage;
import com.anthropic.cclc.domain.port.LlmClient.StreamHandler;
import com.anthropic.cclc.domain.tool.ToolUseRequest;

import java.util.Objects;

public final class OutputRenderer implements StreamHandler {

    private static final String ANSI_RESET = "[0m";
    private static final String ANSI_CYAN = "[36m";
    private static final String ANSI_RED = "[31m";

    private final TerminalIo terminalIo;
    private final boolean colorEnabled;

    public OutputRenderer(TerminalIo terminalIo) {
        this(terminalIo, isTty());
    }

    public OutputRenderer(TerminalIo terminalIo, boolean colorEnabled) {
        this.terminalIo = Objects.requireNonNull(terminalIo, "terminalIo");
        this.colorEnabled = colorEnabled;
    }

    @Override
    public void onPartialText(String delta) {
        terminalIo.write(delta);
    }

    @Override
    public void onComplete(AiMessage message) {
        terminalIo.writeLine("");
        for (ToolUseRequest req : message.toolUseRequests()) {
            renderToolCall(req);
        }
    }

    @Override
    public void onError(Throwable error) {
        String text = "error: " + error.getMessage();
        terminalIo.writeError(colorEnabled ? ANSI_RED + text + ANSI_RESET : text);
    }

    private void renderToolCall(ToolUseRequest req) {
        String text = "→ " + req.toolName() + "(" + req.argumentsJson() + ")";
        terminalIo.writeLine(colorEnabled ? ANSI_CYAN + text + ANSI_RESET : text);
    }

    private static boolean isTty() {
        return System.console() != null;
    }
}
