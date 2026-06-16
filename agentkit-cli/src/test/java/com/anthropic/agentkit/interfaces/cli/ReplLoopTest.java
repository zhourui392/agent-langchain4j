package com.anthropic.agentkit.interfaces.cli;

import com.anthropic.agentkit.testsupport.io.ScriptedTerminalIo;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ReplLoopTest {

    @Test
    void singleLineInputIsForwardedToHandler() {
        ScriptedTerminalIo io = ScriptedTerminalIo.builder()
                .input("hello")
                .build();
        List<String> received = new ArrayList<>();
        ReplLoop loop = new ReplLoop(io, received::add);

        loop.run();

        assertThat(received).containsExactly("hello");
    }

    @Test
    void blankLinesAreSkipped() {
        ScriptedTerminalIo io = ScriptedTerminalIo.builder()
                .input("")
                .input("   ")
                .input("real input")
                .build();
        List<String> received = new ArrayList<>();
        ReplLoop loop = new ReplLoop(io, received::add);

        loop.run();

        assertThat(received).containsExactly("real input");
    }

    @Test
    void exitTerminatesLoop() {
        ScriptedTerminalIo io = ScriptedTerminalIo.builder()
                .input("first")
                .input("exit")
                .input("should-not-be-seen")
                .build();
        List<String> received = new ArrayList<>();
        ReplLoop loop = new ReplLoop(io, received::add);

        loop.run();

        assertThat(received).containsExactly("first");
    }

    @Test
    void quitTerminatesLoopCaseInsensitive() {
        ScriptedTerminalIo io = ScriptedTerminalIo.builder()
                .input("QUIT")
                .input("unreachable")
                .build();
        List<String> received = new ArrayList<>();
        ReplLoop loop = new ReplLoop(io, received::add);

        loop.run();

        assertThat(received).isEmpty();
    }

    @Test
    void backslashContinuesOntoNextLine() {
        ScriptedTerminalIo io = ScriptedTerminalIo.builder()
                .input("line one\\")
                .input("line two")
                .build();
        List<String> received = new ArrayList<>();
        ReplLoop loop = new ReplLoop(io, received::add);

        loop.run();

        assertThat(received).containsExactly("line one\nline two");
    }

    @Test
    void fenceCapturesMultilineBlock() {
        ScriptedTerminalIo io = ScriptedTerminalIo.builder()
                .input("```")
                .input("first")
                .input("second")
                .input("```")
                .build();
        List<String> received = new ArrayList<>();
        ReplLoop loop = new ReplLoop(io, received::add);

        loop.run();

        assertThat(received).containsExactly("```\nfirst\nsecond\n```\n");
    }

    @Test
    void endOfInputTerminatesLoop() {
        ScriptedTerminalIo io = ScriptedTerminalIo.builder().build();
        List<String> received = new ArrayList<>();
        ReplLoop loop = new ReplLoop(io, received::add);

        loop.run();

        assertThat(received).isEmpty();
    }
}
