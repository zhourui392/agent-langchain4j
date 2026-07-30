package com.anthropic.agentkit.infrastructure.tools;

import com.anthropic.agentkit.domain.tool.ExecutionContext;
import com.anthropic.agentkit.domain.tool.Tool;
import com.anthropic.agentkit.domain.tool.ToolArguments;
import com.anthropic.agentkit.domain.tool.ToolResult;
import com.anthropic.agentkit.infrastructure.tools.support.EsReadClient;
import com.anthropic.agentkit.infrastructure.tools.support.LogSanitizer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.Objects;
import java.util.Set;

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
    private static final ObjectMapper JSON = new ObjectMapper();

    private static final int DEFAULT_SIZE = 10;

    private final EsReadClient client;
    private final Set<String> allowedIndices;
    private final boolean strictIndexScope;

    public EsReadTool(EsReadClient client) {
        this(client, Set.of(), false);
    }

    public EsReadTool(EsReadClient client, Set<String> allowedIndices) {
        this(client, allowedIndices, true);
    }

    private EsReadTool(EsReadClient client, Set<String> allowedIndices,
                       boolean strictIndexScope) {
        this.client = Objects.requireNonNull(client, "client");
        this.allowedIndices = cleanIndices(allowedIndices);
        this.strictIndexScope = strictIndexScope;
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
        if (strictIndexScope && !allowedIndex(index)) {
            return ToolResult.error("EsRead index is not allowlisted");
        }
        if ((op.equals("search") || op.equals("count"))
                && !safeQuery(args.getString("query", "{}"))) {
            return ToolResult.error("EsRead query violates the bounded query policy");
        }
        try {
            ToolResult result = executeRead(args, op, index);
            log.info("es read completed: op={}, index={}, success={}, chars={}, durationMs={}",
                    op, index, result.success(), result.content().length(), elapsedMs(startNs));
            return result;
        } catch (IOException ex) {
            log.error("es read failed: op={}, failureType={}",
                    op, ex.getClass().getSimpleName());
            return ToolResult.error("EsRead failed: backend request could not be completed");
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

    private boolean allowedIndex(String index) {
        return allowedIndices.stream().anyMatch(pattern -> globMatches(pattern, index));
    }

    private static boolean safeQuery(String query) {
        if (query == null || query.length() > 32_768) {
            return false;
        }
        try {
            JsonNode root = JSON.readTree(query);
            QueryShape shape = inspect(root, 0);
            return root != null && root.isObject() && shape.safe()
                    && shape.maxDepth() <= 12 && shape.nodes() <= 200;
        } catch (IOException failure) {
            return false;
        }
    }

    private static QueryShape inspect(JsonNode node, int depth) {
        if (node == null || depth > 12) {
            return new QueryShape(false, depth, 1);
        }
        boolean safe = !node.isObject() || java.util.stream.StreamSupport.stream(
                        java.util.Spliterators.spliteratorUnknownSize(
                                node.fieldNames(), java.util.Spliterator.ORDERED), false)
                .noneMatch(name -> Set.of("script", "script_score", "runtime_mappings",
                        "percolate").contains(name.toLowerCase()));
        int maxDepth = depth;
        int nodes = 1;
        for (JsonNode child : node) {
            QueryShape nested = inspect(child, depth + 1);
            safe &= nested.safe();
            maxDepth = Math.max(maxDepth, nested.maxDepth());
            nodes += nested.nodes();
        }
        return new QueryShape(safe, maxDepth, nodes);
    }

    private static boolean globMatches(String pattern, String index) {
        String regex = "\\Q" + pattern.replace("*", "\\E.*\\Q") + "\\E";
        return index.matches(regex);
    }

    private static Set<String> cleanIndices(Set<String> values) {
        if (values == null) {
            return Set.of();
        }
        return values.stream().map(value -> Objects.requireNonNull(value, "index").trim())
                .filter(value -> !value.isEmpty())
                .peek(value -> {
                    if (!value.matches("[A-Za-z0-9][A-Za-z0-9._*-]{0,254}")
                            || value.contains("..")) {
                        throw new IllegalArgumentException("ES index allowlist is invalid");
                    }
                }).collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    private record QueryShape(boolean safe, int maxDepth, int nodes) {
    }

    private static long elapsedMs(long startNs) {
        return (System.nanoTime() - startNs) / 1_000_000L;
    }
}
