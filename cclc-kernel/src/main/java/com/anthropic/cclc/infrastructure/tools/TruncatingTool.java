package com.anthropic.cclc.infrastructure.tools;

import com.anthropic.cclc.domain.tool.ExecutionContext;
import com.anthropic.cclc.domain.tool.Tool;
import com.anthropic.cclc.domain.tool.ToolArguments;
import com.anthropic.cclc.domain.tool.ToolResult;
import com.anthropic.cclc.infrastructure.tools.support.ToolResultTruncator;

import java.util.Objects;

/**
 * Decorates any {@link Tool} so its output is truncated before it enters the
 * conversation. Metadata (name/schema/read-only) passes through unchanged; only
 * the result content is trimmed.
 *
 * @author zhourui(V33215020)
 * @since 2026-06-08
 */
public final class TruncatingTool implements Tool {

    private final Tool delegate;
    private final ToolResultTruncator truncator;

    public TruncatingTool(Tool delegate, ToolResultTruncator truncator) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.truncator = Objects.requireNonNull(truncator, "truncator");
    }

    @Override
    public String name() {
        return delegate.name();
    }

    @Override
    public String description() {
        return delegate.description();
    }

    @Override
    public String inputSchema() {
        return delegate.inputSchema();
    }

    @Override
    public boolean isReadOnly() {
        return delegate.isReadOnly();
    }

    @Override
    public ToolResult execute(ToolArguments args, ExecutionContext ctx) {
        ToolResult result = delegate.execute(args, ctx);
        return new ToolResult(result.success(), truncator.truncate(result.content()));
    }
}
