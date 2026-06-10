package com.anthropic.cclc.infrastructure.tools;

import com.anthropic.cclc.domain.tool.ExecutionContext;
import com.anthropic.cclc.domain.tool.Tool;
import com.anthropic.cclc.domain.tool.ToolArguments;
import com.anthropic.cclc.domain.tool.ToolResult;
import com.anthropic.cclc.infrastructure.tools.support.GrepBackend;
import com.anthropic.cclc.infrastructure.tools.support.JavaRegexGrepBackend;
import com.anthropic.cclc.infrastructure.tools.support.RipgrepBackend;

public final class GrepTool implements Tool {

    private static final String INPUT_SCHEMA = """
            {"type":"object","properties":{\
            "pattern":{"type":"string","description":"regex"},\
            "glob":{"type":"string","description":"file glob filter"},\
            "context":{"type":"integer","description":"lines of context around each match"}\
            },"required":["pattern"]}""";

    private final GrepBackend backend;

    public GrepTool() {
        this(autoDetect());
    }

    public GrepTool(GrepBackend backend) {
        this.backend = backend;
    }

    public static GrepBackend autoDetect() {
        return RipgrepBackend.isAvailable() ? new RipgrepBackend() : new JavaRegexGrepBackend();
    }

    @Override public String name() { return "Grep"; }
    @Override public String description() { return "Search file contents for a regex pattern"; }
    @Override public String inputSchema() { return INPUT_SCHEMA; }
    @Override public boolean isReadOnly() { return true; }

    @Override
    public ToolResult execute(ToolArguments args, ExecutionContext ctx) {
        GrepBackend.GrepRequest request = new GrepBackend.GrepRequest(
                args.getString("pattern"),
                args.getString("glob", ""),
                args.getInt("context", 0));
        GrepBackend.GrepResult result = backend.search(request, ctx.cwd());
        return result.success()
                ? ToolResult.ok(result.output().isEmpty() ? "(no matches)" : result.output())
                : ToolResult.error(result.error());
    }
}
