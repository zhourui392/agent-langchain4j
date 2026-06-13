package com.anthropic.cclc.infrastructure.tools;

import com.anthropic.cclc.domain.tool.ExecutionContext;
import com.anthropic.cclc.domain.tool.Tool;
import com.anthropic.cclc.domain.tool.ToolArguments;
import com.anthropic.cclc.domain.tool.ToolResult;
import com.anthropic.cclc.infrastructure.tools.support.MysqlReadClient;
import com.anthropic.cclc.infrastructure.tools.support.LogSanitizer;

import java.sql.SQLException;
import java.util.Arrays;
import java.util.Objects;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Read-only MySQL tool: runs a single read statement and returns the rows.
 * A SQL guard rejects anything that is not a plain read.
 *
 * @author zhourui(V33215020)
 * @since 2026-06-08
 */
public final class MysqlReadTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(MysqlReadTool.class);

    private static final int DEFAULT_MAX_ROWS = 100;
    private static final int MAX_ROWS_LIMIT = 100;
    private static final Set<String> READ_VERBS = Set.of(
            "SELECT", "WITH", "SHOW", "DESCRIBE", "DESC", "EXPLAIN");

    private final MysqlReadClient client;

    public MysqlReadTool(MysqlReadClient client) {
        this.client = Objects.requireNonNull(client, "client");
    }

    @Override
    public String name() {
        return "MysqlRead";
    }

    @Override
    public String description() {
        return "Read-only MySQL: run one SELECT/WITH/SHOW/DESCRIBE/EXPLAIN statement; returns rows.";
    }

    @Override
    public String inputSchema() {
        return "{\"type\":\"object\",\"properties\":{"
                + "\"sql\":{\"type\":\"string\"},"
                + "\"maxRows\":{\"type\":\"integer\"}},"
                + "\"required\":[\"sql\"]}";
    }

    @Override
    public boolean isReadOnly() {
        return true;
    }

    @Override
    public ToolResult execute(ToolArguments args, ExecutionContext ctx) {
        long startNs = System.nanoTime();
        String sql = args.getString("sql", "").trim();
        if (sql.isEmpty()) {
            log.warn("mysql read blocked: reason=missing_sql");
            return ToolResult.error("MysqlRead requires 'sql'");
        }
        log.debug("mysql read args: sql={}, maxRows={}", LogSanitizer.summarizeSql(sql), maxRows(args));
        if (!isReadOnlySql(sql)) {
            log.warn("mysql read blocked: reason=non_read_only, sql={}", LogSanitizer.summarizeSql(sql));
            return ToolResult.error(
                    "MysqlRead allows only read-only statements (SELECT/WITH/SHOW/DESCRIBE/EXPLAIN)");
        }
        try {
            String output = client.query(sql, maxRows(args));
            log.info("mysql read completed: verb={}, maxRows={}, lines={}, chars={}, durationMs={}",
                    firstVerb(sql), maxRows(args), lineCount(output), output.length(), elapsedMs(startNs));
            return ToolResult.ok(output);
        } catch (SQLException ex) {
            log.error("mysql read failed: sql={}", LogSanitizer.summarizeSql(sql), ex);
            return ToolResult.error("MysqlRead failed: " + ex.getMessage());
        }
    }

    private static int maxRows(ToolArguments args) {
        int requested = args.getInt("maxRows", DEFAULT_MAX_ROWS);
        if (requested <= 0) {
            return DEFAULT_MAX_ROWS;
        }
        return Math.min(requested, MAX_ROWS_LIMIT);
    }

    private static boolean isReadOnlySql(String sql) {
        long statementCount = Arrays.stream(sql.split(";"))
                .filter(part -> !part.isBlank())
                .count();
        if (statementCount != 1) {
            return false;
        }
        String firstToken = sql.split("\\s+", 2)[0].toUpperCase();
        return READ_VERBS.contains(firstToken);
    }

    private static String firstVerb(String sql) {
        return sql.split("\\s+", 2)[0].toUpperCase();
    }

    private static int lineCount(String output) {
        return output == null || output.isEmpty() ? 0 : output.split("\\R", -1).length;
    }

    private static long elapsedMs(long startNs) {
        return (System.nanoTime() - startNs) / 1_000_000L;
    }
}
