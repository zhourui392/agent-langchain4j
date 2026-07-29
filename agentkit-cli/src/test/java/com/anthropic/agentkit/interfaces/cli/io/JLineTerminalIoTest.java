package com.anthropic.agentkit.interfaces.cli.io;

import com.anthropic.agentkit.application.io.TerminalIo.PromptAnswer;
import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.UserInterruptException;
import org.jline.terminal.Terminal;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class JLineTerminalIoTest {

    @Test
    void readLineReturnsLineFromLineReader() {
        LineReader reader = mock(LineReader.class);
        when(reader.readLine("> ")).thenReturn("hello");
        JLineTerminalIo io = newIo(reader);

        Optional<String> line = io.readLine("> ");

        assertThat(line).contains("hello");
    }

    @Test
    void readLineReturnsEmptyOnEndOfFile() {
        LineReader reader = mock(LineReader.class);
        when(reader.readLine(eq("> "))).thenThrow(new EndOfFileException());
        JLineTerminalIo io = newIo(reader);

        assertThat(io.readLine("> ")).isEmpty();
    }

    @Test
    void readLineReturnsBlankLineOnUserInterrupt() {
        LineReader reader = mock(LineReader.class);
        when(reader.readLine(eq("> "))).thenThrow(new UserInterruptException(""));
        JLineTerminalIo io = newIo(reader);

        assertThat(io.readLine("> ")).contains("");
    }

    @Test
    void writeAndWriteLineGoToStdout() {
        LineReader reader = mock(LineReader.class);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        JLineTerminalIo io = new JLineTerminalIo(reader,
                new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8));

        io.write("Hi");
        io.writeLine("!");

        assertThat(out.toString(StandardCharsets.UTF_8)).isEqualTo("Hi!\n");
        assertThat(err.toString(StandardCharsets.UTF_8)).isEmpty();
    }

    @Test
    void writeErrorGoesToStderr() {
        LineReader reader = mock(LineReader.class);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        JLineTerminalIo io = new JLineTerminalIo(reader,
                new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8));

        io.writeError("boom");

        assertThat(out.toString(StandardCharsets.UTF_8)).isEmpty();
        assertThat(err.toString(StandardCharsets.UTF_8)).isEqualTo("boom\n");
    }

    @Test
    void promptMapsAffirmativeInputToAllowOnce() {
        LineReader reader = mock(LineReader.class);
        when(reader.readLine("Allow Bash? [y/N/a(lways)] ")).thenReturn("y");
        JLineTerminalIo io = newIo(reader);

        assertThat(io.promptYesNoAlways("Allow Bash?")).isEqualTo(PromptAnswer.ALLOW_ONCE);
    }

    @Test
    void promptMapsAlwaysInputToAllowAlways() {
        LineReader reader = mock(LineReader.class);
        when(reader.readLine("Allow Bash? [y/N/a(lways)] ")).thenReturn("a");
        JLineTerminalIo io = newIo(reader);

        assertThat(io.promptYesNoAlways("Allow Bash?")).isEqualTo(PromptAnswer.ALLOW_ALWAYS);
    }

    @Test
    void promptMapsEmptyAndOtherInputsToDeny() {
        LineReader reader = mock(LineReader.class);
        when(reader.readLine("Allow Bash? [y/N/a(lways)] ")).thenReturn("");
        JLineTerminalIo io = newIo(reader);

        assertThat(io.promptYesNoAlways("Allow Bash?")).isEqualTo(PromptAnswer.DENY);
    }

    @Test
    void promptMapsEndOfFileToDeny() {
        LineReader reader = mock(LineReader.class);
        when(reader.readLine("Allow Bash? [y/N/a(lways)] ")).thenThrow(new EndOfFileException());
        JLineTerminalIo io = newIo(reader);

        assertThat(io.promptYesNoAlways("Allow Bash?")).isEqualTo(PromptAnswer.DENY);
    }

    @Test
    void registersRealTerminalSigintHandler() {
        LineReader reader = mock(LineReader.class);
        Terminal terminal = mock(Terminal.class);
        AtomicBoolean called = new AtomicBoolean();
        JLineTerminalIo io = new JLineTerminalIo(
                reader, new PrintStream(new ByteArrayOutputStream()),
                new PrintStream(new ByteArrayOutputStream()), terminal);
        when(terminal.handle(eq(Terminal.Signal.INT), any()))
                .thenAnswer(invocation -> {
                    Terminal.SignalHandler handler = invocation.getArgument(1);
                    handler.handle(Terminal.Signal.INT);
                    return Terminal.SignalHandler.SIG_DFL;
                });

        io.onSigint(() -> called.set(true));

        verify(terminal).handle(eq(Terminal.Signal.INT), any());
        assertThat(called).isTrue();
    }

    private static JLineTerminalIo newIo(LineReader reader) {
        return new JLineTerminalIo(reader,
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8),
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8));
    }
}
