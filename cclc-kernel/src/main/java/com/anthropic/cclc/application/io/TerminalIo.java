package com.anthropic.cclc.application.io;

import java.util.Optional;

public interface TerminalIo {

    Optional<String> readLine(String prompt);

    void write(String text);

    void writeLine(String text);

    void writeError(String text);

    PromptAnswer promptYesNoAlways(String question);

    enum PromptAnswer {
        ALLOW_ONCE,
        ALLOW_ALWAYS,
        DENY
    }
}
