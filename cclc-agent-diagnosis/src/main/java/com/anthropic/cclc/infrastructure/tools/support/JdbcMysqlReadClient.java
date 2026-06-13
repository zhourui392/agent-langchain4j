package com.anthropic.cclc.infrastructure.tools.support;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Objects;
import java.util.StringJoiner;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link MysqlReadClient} over {@code java.sql} only — the MySQL JDBC driver is
 * loaded from the runtime classpath (provided by the host), so the engine carries
 * no MySQL compile dependency. Read-only connection is set as defence in depth;
 * the statement guard in {@code MysqlReadTool} is the real enforcement. Thin
 * adapter, covered by integration rather than unit tests.
 *
 * @author zhourui(V33215020)
 * @since 2026-06-08
 */
public final class JdbcMysqlReadClient implements MysqlReadClient {

    private static final Logger log = LoggerFactory.getLogger(JdbcMysqlReadClient.class);

    private final String jdbcUrl;
    private final String user;
    private final String password;

    public JdbcMysqlReadClient(String jdbcUrl, String user, String password) {
        this.jdbcUrl = Objects.requireNonNull(jdbcUrl, "jdbcUrl");
        this.user = user;
        this.password = password;
    }

    @Override
    public String query(String sql, int maxRows) throws SQLException {
        long startNs = System.nanoTime();
        log.debug("jdbc mysql query started: jdbcUrl={}, maxRows={}, sql={}",
                LogSanitizer.summarizeCommand(jdbcUrl), maxRows, LogSanitizer.summarizeSql(sql));
        try (Connection connection = DriverManager.getConnection(jdbcUrl, user, password)) {
            connection.setReadOnly(true);
            try (Statement statement = connection.createStatement()) {
                statement.setMaxRows(Math.max(0, maxRows));
                try (ResultSet resultSet = statement.executeQuery(sql)) {
                    String output = format(resultSet);
                    log.debug("jdbc mysql query completed: rows={}, chars={}, durationMs={}",
                            rowCount(output), output.length(), elapsedMs(startNs));
                    return output;
                }
            }
        }
    }

    private static String format(ResultSet resultSet) throws SQLException {
        ResultSetMetaData meta = resultSet.getMetaData();
        int columns = meta.getColumnCount();
        StringBuilder out = new StringBuilder(header(meta, columns)).append('\n');
        int rows = 0;
        while (resultSet.next()) {
            out.append(row(resultSet, columns)).append('\n');
            rows++;
        }
        return out.append('(').append(rows).append(" rows)").toString();
    }

    private static String header(ResultSetMetaData meta, int columns) throws SQLException {
        StringJoiner joiner = new StringJoiner("\t");
        for (int i = 1; i <= columns; i++) {
            joiner.add(meta.getColumnLabel(i));
        }
        return joiner.toString();
    }

    private static String row(ResultSet resultSet, int columns) throws SQLException {
        StringJoiner joiner = new StringJoiner("\t");
        for (int i = 1; i <= columns; i++) {
            Object value = resultSet.getObject(i);
            joiner.add(value == null ? "NULL" : value.toString());
        }
        return joiner.toString();
    }

    private static int rowCount(String output) {
        int marker = output.lastIndexOf('(');
        int suffix = output.lastIndexOf(" rows)");
        if (marker < 0 || suffix <= marker) {
            return -1;
        }
        try {
            return Integer.parseInt(output.substring(marker + 1, suffix));
        } catch (NumberFormatException ex) {
            return -1;
        }
    }

    private static long elapsedMs(long startNs) {
        return (System.nanoTime() - startNs) / 1_000_000L;
    }
}
