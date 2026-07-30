package com.anthropic.agentkit.infrastructure.tools.support;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Objects;
import java.util.StringJoiner;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

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
    private static final int QUERY_TIMEOUT_SECONDS = 10;
    private static final int MAX_FIELD_BYTES = 64 * 1024;
    private static final int MAX_OUTPUT_BYTES = 1024 * 1024;
    private static final String TRUNCATED = "\n...<truncated>...";

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
                statement.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
                statement.setMaxFieldSize(MAX_FIELD_BYTES);
                try (ResultSet resultSet = statement.executeQuery(sql)) {
                    String output = format(resultSet);
                    log.debug("jdbc mysql query completed: rows={}, chars={}, durationMs={}",
                            rowCount(output), output.length(), elapsedMs(startNs));
                    return output;
                }
            }
        }
    }

    static String format(ResultSet resultSet) throws SQLException {
        ResultSetMetaData meta = resultSet.getMetaData();
        int columns = meta.getColumnCount();
        BoundedUtf8Output out = new BoundedUtf8Output(MAX_OUTPUT_BYTES, TRUNCATED);
        out.append(header(meta, columns)).append("\n");
        int rows = 0;
        while (!out.truncated() && resultSet.next()) {
            out.append(row(resultSet, columns)).append("\n");
            rows++;
        }
        if (!out.truncated()) {
            out.append("(").append(Integer.toString(rows)).append(" rows)");
        }
        return out.value();
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

    private static final class BoundedUtf8Output {
        private final ByteArrayOutputStream output;
        private final int contentLimit;
        private final byte[] truncationMarker;
        private boolean truncated;

        private BoundedUtf8Output(int maxBytes, String marker) {
            this.output = new ByteArrayOutputStream(Math.min(maxBytes, 8192));
            this.truncationMarker = marker.getBytes(StandardCharsets.UTF_8);
            this.contentLimit = maxBytes - truncationMarker.length;
        }

        private BoundedUtf8Output append(String text) {
            if (truncated || text == null || text.isEmpty()) {
                return this;
            }
            byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
            if (output.size() + bytes.length <= contentLimit) {
                output.writeBytes(bytes);
                return this;
            }
            appendCodePointsWithin(text, contentLimit - output.size());
            output.writeBytes(truncationMarker);
            truncated = true;
            return this;
        }

        private void appendCodePointsWithin(String text, int remaining) {
            for (int index = 0; index < text.length() && remaining > 0; ) {
                int codePoint = text.codePointAt(index);
                byte[] encoded = new String(Character.toChars(codePoint))
                        .getBytes(StandardCharsets.UTF_8);
                if (encoded.length > remaining) {
                    return;
                }
                output.writeBytes(encoded);
                remaining -= encoded.length;
                index += Character.charCount(codePoint);
            }
        }

        private boolean truncated() {
            return truncated;
        }

        private String value() {
            return output.toString(StandardCharsets.UTF_8);
        }
    }
}
