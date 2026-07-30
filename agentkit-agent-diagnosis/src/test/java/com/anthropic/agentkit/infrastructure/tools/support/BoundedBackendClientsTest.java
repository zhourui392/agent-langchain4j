package com.anthropic.agentkit.infrastructure.tools.support;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * @author alex
 */
class BoundedBackendClientsTest {

    @Test
    void mysqlRenderingStopsBeforeOneMegabyteAndKeepsValidUtf8() throws Exception {
        ResultSet rows = mock(ResultSet.class);
        ResultSetMetaData metadata = mock(ResultSetMetaData.class);
        when(rows.getMetaData()).thenReturn(metadata);
        when(metadata.getColumnCount()).thenReturn(1);
        when(metadata.getColumnLabel(1)).thenReturn("payload");
        when(rows.next()).thenReturn(true);
        when(rows.getObject(1)).thenReturn("诊断".repeat(300_000));

        String output = JdbcMysqlReadClient.format(rows);

        assertThat(output).contains("...<truncated>...");
        assertThat(output.getBytes(StandardCharsets.UTF_8).length)
                .isLessThanOrEqualTo(1024 * 1024);
    }

    @Test
    void redisLargeBulkValueIsTruncatedBeforeMaterializingTheWholeReply() throws Exception {
        byte[] body = "x".repeat(1024 * 1024 + 256).getBytes(StandardCharsets.UTF_8);
        byte[] prefix = ("$" + body.length + "\r\n").getBytes(StandardCharsets.UTF_8);
        byte[] wire = new byte[prefix.length + body.length + 2];
        System.arraycopy(prefix, 0, wire, 0, prefix.length);
        System.arraycopy(body, 0, wire, prefix.length, body.length);
        wire[wire.length - 2] = '\r';
        wire[wire.length - 1] = '\n';

        String output = SocketRedisClient.renderReply(new ByteArrayInputStream(wire));

        assertThat(output).contains("...<truncated>...");
        assertThat(output.getBytes(StandardCharsets.UTF_8).length)
                .isLessThanOrEqualTo(1024 * 1024);
    }

    @Test
    void dubboResponseFailsClosedBeforeOneMegabyte() {
        byte[] oversized = "x".repeat(1024 * 1024 + 1).getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> SocketDubboTelnetClient.readUntilPrompt(
                new ByteArrayInputStream(oversized)))
                .isInstanceOf(java.io.IOException.class)
                .hasMessageContaining("configured limit");
    }
}
