package com.anthropic.agentkit.infrastructure.tools;

import com.anthropic.agentkit.domain.tool.ExecutionContext;
import com.anthropic.agentkit.domain.tool.Tool;
import com.anthropic.agentkit.domain.tool.ToolArguments;
import com.anthropic.agentkit.domain.tool.ToolResult;
import com.anthropic.agentkit.infrastructure.tools.support.MysqlReadClient;
import com.anthropic.agentkit.infrastructure.tools.support.LogSanitizer;

import java.sql.SQLException;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
            "SELECT", "SHOW", "DESCRIBE", "DESC", "EXPLAIN");
    private static final Pattern FORBIDDEN = Pattern.compile(
            "(?i)\\b(INTO\\s+(?:OUTFILE|DUMPFILE)|FOR\\s+UPDATE|LOCK\\s+IN\\s+SHARE\\s+MODE|"
                    + "SLEEP\\s*\\(|BENCHMARK\\s*\\(|GET_LOCK\\s*\\(|RELEASE_LOCK\\s*\\(|"
                    + "LOAD_FILE\\s*\\(|PROCEDURE\\s+ANALYSE)\\b");
    private static final Pattern QUALIFIED_SOURCE = Pattern.compile(
            "(?i)\\b(?:FROM|JOIN)\\s+`?([A-Za-z_][A-Za-z0-9_$]*)`?\\s*\\.");
    private static final Pattern SHOW_SCHEMA = Pattern.compile(
            "(?i)\\bSHOW\\s+(?:TABLES|COLUMNS)\\s+(?:FROM|IN)\\s+`?([A-Za-z_][A-Za-z0-9_$]*)`?");

    private final MysqlReadClient client;
    private final Set<String> allowedSchemas;

    public MysqlReadTool(MysqlReadClient client) {
        this(client, Set.of());
    }

    public MysqlReadTool(MysqlReadClient client, Set<String> allowedSchemas) {
        this.client = Objects.requireNonNull(client, "client");
        this.allowedSchemas = cleanSchemas(allowedSchemas);
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
        if (!isReadOnlySql(sql, allowedSchemas)) {
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
            log.error("mysql read failed: verb={}, failureType={}",
                    firstVerb(sql), ex.getClass().getSimpleName());
            return ToolResult.error("MysqlRead failed: database query could not be completed");
        }
    }

    private static int maxRows(ToolArguments args) {
        int requested = args.getInt("maxRows", DEFAULT_MAX_ROWS);
        if (requested <= 0) {
            return DEFAULT_MAX_ROWS;
        }
        return Math.min(requested, MAX_ROWS_LIMIT);
    }

    private static boolean isReadOnlySql(String sql, Set<String> allowedSchemas) {
        if (sql.length() > 20_000 || sql.contains("/*!") || hasMultipleStatements(sql)) {
            return false;
        }
        String normalized = stripLiteralsAndComments(sql).trim();
        if (normalized.isEmpty() || FORBIDDEN.matcher(normalized).find()) {
            return false;
        }
        String firstToken = normalized.split("\\s+", 2)[0].toUpperCase();
        return READ_VERBS.contains(firstToken) && schemasAllowed(normalized, allowedSchemas);
    }

    private static boolean hasMultipleStatements(String sql) {
        boolean quoted = false;
        char quote = 0;
        int statements = 1;
        for (int index = 0; index < sql.length(); index++) {
            char value = sql.charAt(index);
            if (quoted && value == quote && (index == 0 || sql.charAt(index - 1) != '\\')) {
                quoted = false;
            } else if (!quoted && (value == '\'' || value == '"')) {
                quoted = true;
                quote = value;
            } else if (!quoted && value == ';'
                    && !sql.substring(index + 1).trim().isEmpty()) {
                statements++;
            }
        }
        return quoted || statements != 1;
    }

    private static String stripLiteralsAndComments(String sql) {
        String withoutBlock = sql.replaceAll("(?s)/\\*.*?\\*/", " ");
        String withoutLine = withoutBlock.replaceAll("(?m)--[^\\r\\n]*$|#[^\\r\\n]*$", " ");
        return withoutLine.replaceAll("'(?:''|\\\\.|[^'])*'|\"(?:\"\"|\\\\.|[^\"])*\"", "''");
    }

    private static boolean schemasAllowed(String sql, Set<String> allowedSchemas) {
        return matchesAllowed(QUALIFIED_SOURCE.matcher(sql), allowedSchemas)
                && matchesAllowed(SHOW_SCHEMA.matcher(sql), allowedSchemas);
    }

    private static boolean matchesAllowed(Matcher matcher, Set<String> allowedSchemas) {
        while (matcher.find()) {
            if (!allowedSchemas.contains(matcher.group(1).toLowerCase())) {
                return false;
            }
        }
        return true;
    }

    private static Set<String> cleanSchemas(Set<String> schemas) {
        if (schemas == null) {
            return Set.of();
        }
        return schemas.stream().map(value -> Objects.requireNonNull(value, "schema").trim().toLowerCase())
                .filter(value -> !value.isEmpty())
                .peek(value -> {
                    if (!value.matches("[a-z_][a-z0-9_$]{0,63}")) {
                        throw new IllegalArgumentException("MySQL schema allowlist is invalid");
                    }
                }).collect(java.util.stream.Collectors.toUnmodifiableSet());
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
