package com.anthropic.agentkit.infrastructure.tools;

import com.anthropic.agentkit.domain.tool.ExecutionContext;
import com.anthropic.agentkit.domain.tool.ToolArguments;
import com.anthropic.agentkit.domain.tool.ToolResult;
import com.anthropic.agentkit.infrastructure.tools.support.MysqlReadClient;
import org.junit.jupiter.api.Test;

import java.nio.file.Paths;
import java.sql.SQLException;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class MysqlReadToolTest {

    private final ExecutionContext ctx = ExecutionContext.at(Paths.get(System.getProperty("user.dir")));
    private final StubMysqlReadClient client = new StubMysqlReadClient();
    private final MysqlReadTool tool = new MysqlReadTool(client);

    @Test
    void runsSelectQuery() {
        client.result = "id\tname\n1\talice";

        ToolResult result = tool.execute(args("SELECT id, name FROM users"), ctx);

        assertThat(result.success()).isTrue();
        assertThat(result.content()).isEqualTo("id\tname\n1\talice");
        assertThat(client.lastSql).isEqualTo("SELECT id, name FROM users");
    }

    @Test
    void allowsShowAndDescribeButRejectsUnparsedCte() {
        client.result = "ok";

        assertThat(tool.execute(args("WITH c AS (SELECT 1) SELECT * FROM c"), ctx).success()).isFalse();
        assertThat(tool.execute(args("SHOW TABLES"), ctx).success()).isTrue();
        assertThat(tool.execute(args("DESCRIBE users"), ctx).success()).isTrue();
    }

    @Test
    void rejectsUpdate() {
        ToolResult result = tool.execute(args("UPDATE users SET name='x' WHERE id=1"), ctx);

        assertThat(result.success()).isFalse();
        assertThat(result.content()).containsIgnoringCase("read-only");
        assertThat(client.calls).isZero();
    }

    @Test
    void rejectsInsert() {
        assertThat(tool.execute(args("INSERT INTO users VALUES (1)"), ctx).success()).isFalse();
        assertThat(client.calls).isZero();
    }

    @Test
    void rejectsStackedWriteAfterSelect() {
        ToolResult result = tool.execute(args("SELECT 1; DROP TABLE users"), ctx);

        assertThat(result.success()).isFalse();
        assertThat(client.calls).isZero();
    }

    @Test
    void missingSqlRejected() {
        ToolResult result = tool.execute(args(""), ctx);

        assertThat(result.success()).isFalse();
        assertThat(result.content()).containsIgnoringCase("sql");
        assertThat(client.calls).isZero();
    }

    @Test
    void backendErrorReturnsError() {
        client.failure = new SQLException("connection refused");

        ToolResult result = tool.execute(args("SELECT 1"), ctx);

        assertThat(result.success()).isFalse();
        assertThat(result.content()).contains("database query could not be completed")
                .doesNotContain("connection refused");
    }

    @Test
    void capsMaxRowsAtProductionLimit() {
        tool.execute(ToolArguments.of(Map.of("sql", "SELECT * FROM users", "maxRows", 500)), ctx);

        assertThat(client.lastMaxRows).isEqualTo(100);
    }

    @Test
    void rejectsCteWriteOutfileLockingAndUnallowlistedSchema() {
        MysqlReadTool guarded = new MysqlReadTool(client, Set.of("app"));

        assertThat(guarded.execute(args(
                "WITH victim AS (SELECT 1) DELETE FROM users"), ctx).success()).isFalse();
        assertThat(guarded.execute(args(
                "SELECT * FROM users INTO OUTFILE '/tmp/leak'"), ctx).success()).isFalse();
        assertThat(guarded.execute(args(
                "SELECT * FROM users FOR UPDATE"), ctx).success()).isFalse();
        assertThat(guarded.execute(args(
                "SELECT * FROM secret.users"), ctx).success()).isFalse();
        assertThat(guarded.execute(args(
                "SELECT * FROM app.users"), ctx).success()).isTrue();
    }

    @Test
    void rejectsExecutableMysqlVersionComment() {
        ToolResult result = tool.execute(args(
                "SELECT * FROM users /*!50000 INTO OUTFILE '/tmp/leak' */"), ctx);

        assertThat(result.success()).isFalse();
        assertThat(client.calls).isZero();
    }

    @Test
    void isReadOnlyTrue() {
        assertThat(tool.isReadOnly()).isTrue();
    }

    private ToolArguments args(String sql) {
        return ToolArguments.of(Map.of("sql", sql));
    }

    private static final class StubMysqlReadClient implements MysqlReadClient {
        private String result = "";
        private SQLException failure;
        private int calls;
        private String lastSql;
        private int lastMaxRows;

        @Override
        public String query(String sql, int maxRows) throws SQLException {
            calls++;
            lastSql = sql;
            lastMaxRows = maxRows;
            if (failure != null) {
                throw failure;
            }
            return result;
        }
    }
}
