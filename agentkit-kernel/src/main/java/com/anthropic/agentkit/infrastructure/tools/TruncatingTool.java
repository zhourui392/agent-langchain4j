package com.anthropic.agentkit.infrastructure.tools;

import com.anthropic.agentkit.domain.tool.ExecutionContext;
import com.anthropic.agentkit.domain.tool.Tool;
import com.anthropic.agentkit.domain.tool.ToolArguments;
import com.anthropic.agentkit.domain.tool.ToolOutputMetadata;
import com.anthropic.agentkit.domain.tool.ToolResult;
import com.anthropic.agentkit.infrastructure.tools.support.ToolResultTruncator;

import java.util.Objects;
import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Decorates any {@link Tool} so its output is truncated before it enters the
 * conversation. Metadata (name/schema/read-only) passes through unchanged; only
 * the result content is trimmed.
 *
 * @author zhourui(V33215020)
 * @since 2026-06-08
 */
public final class TruncatingTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(TruncatingTool.class);

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
        String truncated = truncator.truncate(result.content());
        if (truncated.length() != result.content().length()) {
            log.warn("tool result truncated: tool={}, originalChars={}, truncatedChars={}",
                    delegate.name(), result.content().length(), truncated.length());
            return truncatedResult(result, truncated);
        }
        return result;
    }

    private ToolResult truncatedResult(ToolResult result, String truncated) {
        Map<String, String> metadata = new LinkedHashMap<>(result.metadata());
        metadata.put(ToolOutputMetadata.DISPOSITION_KEY, ToolOutputMetadata.TRUNCATED);
        metadata.put(ToolOutputMetadata.ORIGINAL_CHARACTERS_KEY,
                String.valueOf(result.content().length()));
        metadata.put(ToolOutputMetadata.RETAINED_CHARACTERS_KEY,
                String.valueOf(truncated.length()));
        metadata.putIfAbsent(ToolOutputMetadata.ARTIFACT_KEY, ToolOutputMetadata.OMITTED);
        return ToolResult.of(result.status(), truncated, metadata);
    }
}
