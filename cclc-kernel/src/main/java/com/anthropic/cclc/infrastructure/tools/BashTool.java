package com.anthropic.cclc.infrastructure.tools;

import com.anthropic.cclc.domain.tool.ExecutionContext;
import com.anthropic.cclc.domain.tool.Tool;
import com.anthropic.cclc.domain.tool.ToolArguments;
import com.anthropic.cclc.domain.tool.ToolResult;
import com.anthropic.cclc.infrastructure.tools.support.LogSanitizer;
import com.anthropic.cclc.infrastructure.tools.support.ProcessRunner;
import com.anthropic.cclc.infrastructure.tools.support.ShellSelector;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class BashTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(BashTool.class);
    private static final int DEFAULT_TIMEOUT_MS = 120_000;
    private static final String INPUT_SCHEMA = """
            {"type":"object","properties":{\
            "command":{"type":"string","description":"shell command"},\
            "timeout":{"type":"integer","description":"timeout in ms (default 120000)"}\
            },"required":["command"]}""";

    private final ProcessRunner runner;

    public BashTool() {
        this(new ProcessRunner());
    }

    public BashTool(ProcessRunner runner) {
        this.runner = runner;
    }

    @Override public String name() { return "Bash"; }
    @Override public String description() { return "Execute a shell command"; }
    @Override public String inputSchema() { return INPUT_SCHEMA; }
    @Override public boolean isReadOnly() { return false; }

    @Override
    public ToolResult execute(ToolArguments args, ExecutionContext ctx) {
        long startNs = System.nanoTime();
        String command = args.getString("command");
        int timeoutMs = args.getInt("timeout", DEFAULT_TIMEOUT_MS);
        log.debug("bash execute args: command={}, timeoutMs={}",
                LogSanitizer.summarizeCommand(command), timeoutMs);
        ToolResult result = runner.run(command, ctx.cwd(), timeoutMs, ctx.cancellation());
        long durationMs = elapsedMs(startNs);
        if (result.content().startsWith("timeout after ")) {
            log.warn("bash timed out: shell={}, timeoutMs={}, durationMs={}",
                    ShellSelector.shellName(), timeoutMs, durationMs);
        }
        log.info("bash completed: shell={}, exitCode={}, success={}, durationMs={}, outputChars={}",
                ShellSelector.shellName(), exitCode(result), result.success(), durationMs, result.content().length());
        return result;
    }

    private static int exitCode(ToolResult result) {
        if (result.success()) {
            return 0;
        }
        if (!result.content().startsWith("exit ")) {
            return -1;
        }
        int lineEnd = result.content().indexOf('\n');
        String exitText = lineEnd < 0 ? result.content().substring(5) : result.content().substring(5, lineEnd);
        try {
            return Integer.parseInt(exitText.trim());
        } catch (NumberFormatException ex) {
            return -1;
        }
    }

    private static long elapsedMs(long startNs) {
        return (System.nanoTime() - startNs) / 1_000_000L;
    }
}
