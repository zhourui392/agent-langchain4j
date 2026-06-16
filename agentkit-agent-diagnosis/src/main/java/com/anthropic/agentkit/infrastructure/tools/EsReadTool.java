package com.anthropic.agentkit.infrastructure.tools;

import com.anthropic.agentkit.domain.tool.ExecutionContext;
import com.anthropic.agentkit.domain.tool.Tool;
import com.anthropic.agentkit.domain.tool.ToolArguments;
import com.anthropic.agentkit.domain.tool.ToolResult;
import com.anthropic.agentkit.infrastructure.tools.support.EsReadClient;
import com.anthropic.agentkit.infrastructure.tools.support.LogSanitizer;

import java.io.IOException;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Read-only Elasticsearch tool: search / count / get / mapping.
 *
 * @author zhourui(V33215020)
 * @since 2026-06-08
 */
public final class EsReadTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(EsReadTool.class);

    private static final int DEFAULT_SIZE = 10;

    private final EsReadClient client;

    public EsReadTool(EsReadClient client) {
        this.client = Objects.requireNonNull(client, "client");
    }

    @Override
    public String name() {
        return "EsRead";
    }

    @Override
    public String description() {
        return "Read-only Elasticsearch access: op=search|count|get|mapping against an index.";
    }

    @Override
    public String inputSchema() {
        return "{\"type\":\"object\",\"properties\":{"
                + "\"op\":{\"type\":\"string\",\"enum\":[\"search\",\"count\",\"get\",\"mapping\"]},"
                + "\"index\":{\"type\":\"string\"},"
                + "\"query\":{\"type\":\"string\"},"
                + "\"id\":{\"type\":\"string\"},"
                + "\"size\":{\"type\":\"integer\"}},"
                + "\"required\":[\"op\",\"index\"]}";
    }

    @Override
    public boolean isReadOnly() {
        return true;
    }

    @Override
    public ToolResult execute(ToolArguments args, ExecutionContext ctx) {
        long startNs = System.nanoTime();
        String op = args.getString("op", "").trim();
        String index = args.getString("index", "").trim();
        if (index.isEmpty()) {
            log.warn("es read blocked: reason=missing_index");
            return ToolResult.error("EsRead requires 'index'");
        }
        try {
            ToolResult result = executeRead(args, op, index);
            log.info("es read completed: op={}, index={}, success={}, chars={}, durationMs={}",
                    op, index, result.success(), result.content().length(), elapsedMs(startNs));
            return result;
        } catch (IOException ex) {
            log.error("es read failed: op={}, index={}, query={}",
                    op, index, LogSanitizer.truncate(args.getString("query", "{}"), 80), ex);
            return ToolResult.error("EsRead failed: " + ex.getMessage());
        }
    }

    private ToolResult executeRead(ToolArguments args, String op, String index) throws IOException {
        log.debug("es read args: op={}, index={}, query={}, size={}",
                op, index, LogSanitizer.truncate(args.getString("query", "{}"), 120),
                args.getInt("size", DEFAULT_SIZE));
        return switch (op) {
            case "search" -> ToolResult.ok(
                    client.search(index, args.getString("query", "{}"), args.getInt("size", DEFAULT_SIZE)));
            case "count" -> ToolResult.ok("count: " + client.count(index, args.getString("query", "{}")));
            case "get" -> ToolResult.ok(client.get(index, args.getString("id", "")));
            case "mapping" -> ToolResult.ok(client.mapping(index));
            default -> {
                log.warn("es read blocked: reason=unknown_op, op={}", op);
                yield ToolResult.error("EsRead unknown op: '" + op + "' (use search|count|get|mapping)");
            }
        };
    }

    private static long elapsedMs(long startNs) {
        return (System.nanoTime() - startNs) / 1_000_000L;
    }
}
