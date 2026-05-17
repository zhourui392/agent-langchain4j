package com.anthropic.cclc.interfaces.cli;

import com.anthropic.cclc.application.AgentEventListener;
import com.anthropic.cclc.application.io.TerminalIo;
import com.anthropic.cclc.domain.message.AiMessage;
import com.anthropic.cclc.domain.tool.ToolResult;
import com.anthropic.cclc.domain.tool.ToolUseRequest;

import java.util.Objects;

public final class OutputRenderer implements AgentEventListener {

    private static final String ANSI_RESET = "[0m";
    private static final String ANSI_DIM = "[2m";
    private static final String ANSI_CYAN = "[36m";
    private static final String ANSI_GREEN = "[32m";
    private static final String ANSI_RED = "[31m";

    private static final int PREVIEW_MAX_LINES = 3;
    private static final int PREVIEW_MAX_LINE_WIDTH = 200;
    private static final int ERROR_MAX_WIDTH = 240;

    private final TerminalIo terminalIo;
    private final boolean colorEnabled;
    private boolean assistantTextOpen;

    public OutputRenderer(TerminalIo terminalIo) {
        this(terminalIo, isTty());
    }

    public OutputRenderer(TerminalIo terminalIo, boolean colorEnabled) {
        this.terminalIo = Objects.requireNonNull(terminalIo, "terminalIo");
        this.colorEnabled = colorEnabled;
    }

    @Override
    public void onLlmRequestStart() {
        closeAssistantTextIfOpen();
        terminalIo.writeLine(dim("⏺ thinking…"));
    }

    @Override
    public void onAssistantTextDelta(String delta) {
        terminalIo.write(delta);
        assistantTextOpen = !delta.isEmpty();
    }

    @Override
    public void onToolUseStart(ToolUseRequest request) {
        closeAssistantTextIfOpen();
        String line = "⏵ " + request.toolName() + " " + request.argumentsJson();
        terminalIo.writeLine(cyan(line));
    }

    @Override
    public void onToolUseEnd(ToolUseRequest request, ToolResult result, long durationMs) {
        String marker = result.success() ? "✓" : "✗";
        String color = result.success() ? ANSI_GREEN : ANSI_RED;
        String header = "  " + marker + " " + durationMs + "ms";
        terminalIo.writeLine(paint(color, header));
        renderPreview(result);
    }

    @Override
    public void onTurnComplete(AiMessage finalMessage) {
        closeAssistantTextIfOpen();
    }

    @Override
    public void onError(Throwable error) {
        closeAssistantTextIfOpen();
        String text = "error: " + error.getMessage();
        terminalIo.writeError(paint(ANSI_RED, text));
    }

    private void renderPreview(ToolResult result) {
        if (result.content().isEmpty()) {
            return;
        }
        if (!result.success()) {
            terminalIo.writeLine(dim("    " + truncate(result.content(), ERROR_MAX_WIDTH)));
            return;
        }
        String[] lines = result.content().split("\n", -1);
        int shown = Math.min(lines.length, PREVIEW_MAX_LINES);
        for (int i = 0; i < shown; i++) {
            terminalIo.writeLine(dim("    " + truncate(lines[i], PREVIEW_MAX_LINE_WIDTH)));
        }
        int remaining = lines.length - shown;
        if (remaining > 0) {
            terminalIo.writeLine(dim("    … +" + remaining + " more line" + (remaining == 1 ? "" : "s")));
        }
    }

    private void closeAssistantTextIfOpen() {
        if (assistantTextOpen) {
            terminalIo.writeLine("");
            assistantTextOpen = false;
        }
    }

    private static String truncate(String text, int max) {
        if (text.length() <= max) {
            return text;
        }
        return text.substring(0, max) + "…";
    }

    private String dim(String text) {
        return paint(ANSI_DIM, text);
    }

    private String cyan(String text) {
        return paint(ANSI_CYAN, text);
    }

    private String paint(String color, String text) {
        return colorEnabled ? color + text + ANSI_RESET : text;
    }

    private static boolean isTty() {
        return System.console() != null;
    }
}
