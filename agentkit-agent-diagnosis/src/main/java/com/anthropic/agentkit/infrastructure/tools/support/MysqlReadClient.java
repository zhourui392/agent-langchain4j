package com.anthropic.agentkit.infrastructure.tools.support;

import java.sql.SQLException;

/**
 * Read-only MySQL access seam so {@code MysqlReadTool} can be unit-tested without
 * a live database. The default {@link JdbcMysqlReadClient} uses only {@code java.sql},
 * so the MySQL driver stays a runtime concern (no compile dependency).
 *
 * @author zhourui(V33215020)
 * @since 2026-06-08
 */
public interface MysqlReadClient {

    String query(String sql, int maxRows) throws SQLException;
}
