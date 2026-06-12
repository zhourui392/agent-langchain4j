package com.anthropic.cclc.testsupport.io;

import com.anthropic.cclc.application.io.TerminalIo;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public final class ScriptedTerminalIo implements TerminalIo {

    private final Deque<String> inputs;
    private final Deque<PromptAnswer> answers;
    private final List<String> prompts = new ArrayList<>();
    private final StringBuilder output = new StringBuilder();
    private final StringBuilder errorOutput = new StringBuilder();

    private ScriptedTerminalIo(Deque<String> inputs, Deque<PromptAnswer> answers) {
        this.inputs = inputs;
        this.answers = answers;
    }

    public static Builder builder() {
        return new Builder();
    }

    @Override
    public Optional<String> readLine(String prompt) {
        Objects.requireNonNull(prompt, "prompt");
        return Optional.ofNullable(inputs.pollFirst());
    }

    @Override
    public void write(String text) {
        Objects.requireNonNull(text, "text");
        output.append(text);
    }

    @Override
    public void writeLine(String text) {
        Objects.requireNonNull(text, "text");
        output.append(text).append('\n');
    }

    @Override
    public void writeError(String text) {
        Objects.requireNonNull(text, "text");
        errorOutput.append(text).append('\n');
    }

    @Override
    public PromptAnswer promptYesNoAlways(String question) {
        prompts.add(Objects.requireNonNull(question, "question"));
        PromptAnswer scripted = answers.pollFirst();
        return scripted != null ? scripted : PromptAnswer.DENY;
    }

    public String output() {
        return output.toString();
    }

    public String errorOutput() {
        return errorOutput.toString();
    }

    public List<String> capturedPrompts() {
        return List.copyOf(prompts);
    }

    public static final class Builder {

        private final Deque<String> inputs = new ArrayDeque<>();
        private final Deque<PromptAnswer> answers = new ArrayDeque<>();

        public Builder input(String line) {
            inputs.addLast(Objects.requireNonNull(line, "line"));
            return this;
        }

        public Builder answer(PromptAnswer answer) {
            answers.addLast(Objects.requireNonNull(answer, "answer"));
            return this;
        }

        public ScriptedTerminalIo build() {
            return new ScriptedTerminalIo(inputs, answers);
        }
    }
}
