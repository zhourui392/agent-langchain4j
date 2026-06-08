package com.anthropic.cclc.interfaces.engine;

import com.anthropic.cclc.domain.port.LlmClient;
import com.anthropic.cclc.domain.tool.ExecutionContext;
import com.anthropic.cclc.domain.tool.Tool;
import com.anthropic.cclc.domain.tool.ToolArguments;
import com.anthropic.cclc.domain.tool.ToolRegistry;
import com.anthropic.cclc.domain.tool.ToolResult;

import java.util.Objects;

/**
 * Spawns an isolated read-only sub-agent to chase one hypothesis, returning its
 * final findings as the tool result. The child runs its own {@code AgentExecutor}
 * over a fresh {@code Conversation} with a narrowed tool set, inheriting only the
 * parent's cwd, cancellation, and LLM client. Stub for Red.
 *
 * @author zhourui(V33215020)
 * @since 2026-06-08
 */
public final class SubAgentTool implements Tool {

    private final LlmClient llm;
    private final ToolRegistry childTools;

    public SubAgentTool(LlmClient llm, ToolRegistry childTools) {
        this.llm = Objects.requireNonNull(llm, "llm");
        this.childTools = Objects.requireNonNull(childTools, "childTools");
    }

    @Override
    public String name() {
        return "Task";
    }

    @Override
    public String description() {
        return "Launch an isolated read-only sub-agent to investigate one hypothesis; "
                + "returns its final findings.";
    }

    @Override
    public String inputSchema() {
        return "{\"type\":\"object\",\"properties\":{\"prompt\":{\"type\":\"string\"}},"
                + "\"required\":[\"prompt\"]}";
    }

    @Override
    public boolean isReadOnly() {
        return true;
    }

    @Override
    public ToolResult execute(ToolArguments args, ExecutionContext ctx) {
        return ToolResult.error("not implemented");
    }
}
