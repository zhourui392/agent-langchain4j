package com.anthropic.cclc.infrastructure.tools;

import com.anthropic.cclc.domain.tool.ExecutionContext;
import com.anthropic.cclc.domain.tool.Tool;
import com.anthropic.cclc.domain.tool.ToolArguments;
import com.anthropic.cclc.domain.tool.ToolResult;
import com.anthropic.cclc.infrastructure.tools.support.MysqlReadClient;

import java.sql.SQLException;
import java.util.Arrays;
import java.util.Objects;
import java.util.Set;

/**
 * Read-only MySQL tool: runs a single read statement and returns the rows.
 * A SQL guard rejects anything that is not a plain read.
 *
 * @author zhourui(V33215020)
 * @since 2026-06-08
 */
public final class MysqlReadTool implements Tool {

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
        String sql = args.getString("sql", "").trim();
        if (sql.isEmpty()) {
            return ToolResult.error("MysqlRead requires 'sql'");
        }
        if (!isReadOnlySql(sql)) {
            return ToolResult.error(
                    "MysqlRead allows only read-only statements (SELECT/WITH/SHOW/DESCRIBE/EXPLAIN)");
        }
        try {
            return ToolResult.ok(client.query(sql, maxRows(args)));
        } catch (SQLException ex) {
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
}
