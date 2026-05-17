package com.anthropic.cclc.infrastructure.tools;

import com.anthropic.cclc.domain.tool.ExecutionContext;
import com.anthropic.cclc.domain.tool.Tool;
import com.anthropic.cclc.domain.tool.ToolArguments;
import com.anthropic.cclc.domain.tool.ToolResult;
import com.anthropic.cclc.infrastructure.tools.support.ProcessRunner;

public final class BashTool implements Tool {

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
        String command = args.getString("command");
        int timeoutMs = args.getInt("timeout", DEFAULT_TIMEOUT_MS);
        return runner.run(command, ctx.cwd(), timeoutMs, ctx.cancellation());
    }
}
