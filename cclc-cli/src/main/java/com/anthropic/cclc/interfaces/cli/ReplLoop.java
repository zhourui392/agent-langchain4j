package com.anthropic.cclc.interfaces.cli;

import com.anthropic.cclc.application.io.TerminalIo;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ReplLoop {

    private static final Logger log = LoggerFactory.getLogger(ReplLoop.class);

    private static final String PROMPT = "cclc> ";
    private static final String CONTINUE_PROMPT = "    > ";
    private static final String FENCE = "```";

    private final TerminalIo terminalIo;
    private final Consumer<String> handler;

    public ReplLoop(TerminalIo terminalIo, Consumer<String> handler) {
        this.terminalIo = Objects.requireNonNull(terminalIo, "terminalIo");
        this.handler = Objects.requireNonNull(handler, "handler");
    }

    public void run() {
        while (true) {
            Optional<String> input = readMultiLine();
            if (input.isEmpty()) {
                return;
            }
            String text = input.get();
            if (text.isBlank()) {
                log.debug("repl input skipped: chars=0");
                continue;
            }
            String trimmed = text.trim();
            if ("exit".equalsIgnoreCase(trimmed) || "quit".equalsIgnoreCase(trimmed)) {
                log.info("repl exit command received: command={}", trimmed.toLowerCase());
                return;
            }
            log.debug("repl input accepted: chars={}, multiline={}, fenced={}",
                    text.length(), text.contains("\n"), text.trim().startsWith(FENCE));
            handler.accept(text);
        }
    }

    private Optional<String> readMultiLine() {
        StringBuilder buffer = new StringBuilder();
        boolean insideFence = false;
        String prompt = PROMPT;
        while (true) {
            Optional<String> maybeLine = terminalIo.readLine(prompt);
            if (maybeLine.isEmpty()) {
                return buffer.isEmpty() ? Optional.empty() : Optional.of(buffer.toString());
            }
            String line = maybeLine.get();
            if (insideFence) {
                buffer.append(line).append('\n');
                if (line.trim().equals(FENCE)) {
                    return Optional.of(buffer.toString());
                }
                prompt = CONTINUE_PROMPT;
                continue;
            }
            if (line.trim().startsWith(FENCE)) {
                insideFence = true;
                buffer.append(line).append('\n');
                prompt = CONTINUE_PROMPT;
                continue;
            }
            if (line.endsWith("\\")) {
                buffer.append(line, 0, line.length() - 1).append('\n');
                prompt = CONTINUE_PROMPT;
                continue;
            }
            buffer.append(line);
            return Optional.of(buffer.toString());
        }
    }
}
