package com.anthropic.agentkit.interfaces.cli.io;

import com.anthropic.agentkit.application.io.TerminalIo;
import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.UserInterruptException;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

public final class JLineTerminalIo implements TerminalIo, AutoCloseable {

    private static final String PROMPT_SUFFIX = " [y/N/a(lways)] ";

    private final LineReader reader;
    private final PrintStream stdout;
    private final PrintStream stderr;
    private final Terminal terminal;

    public JLineTerminalIo(LineReader reader, PrintStream stdout, PrintStream stderr) {
        this(reader, stdout, stderr, null);
    }

    JLineTerminalIo(LineReader reader, PrintStream stdout, PrintStream stderr, Terminal terminal) {
        this.reader = Objects.requireNonNull(reader, "reader");
        this.stdout = Objects.requireNonNull(stdout, "stdout");
        this.stderr = Objects.requireNonNull(stderr, "stderr");
        this.terminal = terminal;
    }

    public static JLineTerminalIo openSystem(Path historyFile) throws IOException {
        Terminal terminal = TerminalBuilder.builder().system(true).build();
        LineReaderBuilder builder = LineReaderBuilder.builder().terminal(terminal);
        if (historyFile != null) {
            builder.variable(LineReader.HISTORY_FILE, historyFile);
        }
        LineReader reader = builder.build();
        return new JLineTerminalIo(reader, System.out, System.err, terminal);
    }

    public void onSigint(Runnable handler) {
        Objects.requireNonNull(handler, "handler");
        if (terminal == null) {
            throw new IllegalStateException("SIGINT requires a system terminal");
        }
        terminal.handle(Terminal.Signal.INT, ignored -> handler.run());
    }

    @Override
    public Optional<String> readLine(String prompt) {
        Objects.requireNonNull(prompt, "prompt");
        try {
            return Optional.ofNullable(reader.readLine(prompt));
        } catch (UserInterruptException ie) {
            return Optional.of("");
        } catch (EndOfFileException eof) {
            return Optional.empty();
        }
    }

    @Override
    public void write(String text) {
        stdout.print(text);
        stdout.flush();
    }

    @Override
    public void writeLine(String text) {
        stdout.print(text);
        stdout.print('\n');
        stdout.flush();
    }

    @Override
    public void writeError(String text) {
        stderr.print(text);
        stderr.print('\n');
        stderr.flush();
    }

    @Override
    public PromptAnswer promptYesNoAlways(String question) {
        Objects.requireNonNull(question, "question");
        String answer;
        try {
            answer = reader.readLine(question + PROMPT_SUFFIX);
        } catch (UserInterruptException | EndOfFileException ex) {
            return PromptAnswer.DENY;
        }
        if (answer == null) {
            return PromptAnswer.DENY;
        }
        return switch (answer.trim().toLowerCase()) {
            case "y", "yes" -> PromptAnswer.ALLOW_ONCE;
            case "a", "always" -> PromptAnswer.ALLOW_ALWAYS;
            default -> PromptAnswer.DENY;
        };
    }

    @Override
    public void close() throws IOException {
        if (terminal != null) {
            terminal.close();
        }
    }
}
