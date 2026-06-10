package com.anthropic.cclc.testsupport.io;

import com.anthropic.cclc.application.io.TerminalIo.PromptAnswer;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class ScriptedTerminalIoTest {

    @Test
    void readLineReturnsScriptedInputsInOrder() {
        ScriptedTerminalIo io = ScriptedTerminalIo.builder()
                .input("hello")
                .input("world")
                .build();

        assertThat(io.readLine("cclc> ")).contains("hello");
        assertThat(io.readLine("cclc> ")).contains("world");
    }

    @Test
    void readLineReturnsEmptyWhenScriptExhausted() {
        ScriptedTerminalIo io = ScriptedTerminalIo.builder().input("hi").build();

        io.readLine("> ");
        assertThat(io.readLine("> ")).isEmpty();
    }

    @Test
    void writeAccumulatesIntoOutputBuffer() {
        ScriptedTerminalIo io = ScriptedTerminalIo.builder().build();

        io.write("Hi");
        io.write("! ");
        io.writeLine("How are you?");

        assertThat(io.output()).isEqualTo("Hi! How are you?\n");
    }

    @Test
    void writeErrorGoesToSeparateErrorBuffer() {
        ScriptedTerminalIo io = ScriptedTerminalIo.builder().build();

        io.writeError("boom");

        assertThat(io.output()).isEmpty();
        assertThat(io.errorOutput()).isEqualTo("boom\n");
    }

    @Test
    void promptReturnsScriptedAnswersInOrder() {
        ScriptedTerminalIo io = ScriptedTerminalIo.builder()
                .answer(PromptAnswer.ALLOW_ONCE)
                .answer(PromptAnswer.ALLOW_ALWAYS)
                .answer(PromptAnswer.DENY)
                .build();

        assertThat(io.promptYesNoAlways("ok?")).isEqualTo(PromptAnswer.ALLOW_ONCE);
        assertThat(io.promptYesNoAlways("ok?")).isEqualTo(PromptAnswer.ALLOW_ALWAYS);
        assertThat(io.promptYesNoAlways("ok?")).isEqualTo(PromptAnswer.DENY);
    }

    @Test
    void promptDefaultsToDenyWhenNoAnswersScripted() {
        ScriptedTerminalIo io = ScriptedTerminalIo.builder().build();

        assertThat(io.promptYesNoAlways("ok?")).isEqualTo(PromptAnswer.DENY);
    }

    @Test
    void promptQuestionsAreCapturedForAssertions() {
        ScriptedTerminalIo io = ScriptedTerminalIo.builder()
                .answer(PromptAnswer.ALLOW_ONCE)
                .build();

        io.promptYesNoAlways("Allow Bash?");

        assertThat(io.capturedPrompts()).containsExactly("Allow Bash?");
    }
}
