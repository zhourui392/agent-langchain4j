package com.anthropic.cclc.infrastructure.tools;

import com.anthropic.cclc.domain.tool.ExecutionContext;
import com.anthropic.cclc.domain.tool.ToolArguments;
import com.anthropic.cclc.domain.tool.ToolResult;
import com.anthropic.cclc.infrastructure.tools.support.LogQueryClient;
import com.anthropic.cclc.infrastructure.tools.support.LogQueryRequest;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Paths;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class LogQueryToolTest {

    private final ExecutionContext ctx = ExecutionContext.at(Paths.get(System.getProperty("user.dir")));

    @Test
    void forwardsScopedQueryToClient() {
        RecordingLogQueryClient client = RecordingLogQueryClient.returning("line-1");
        LogQueryTool tool = new LogQueryTool(client);

        ToolResult result = tool.execute(ToolArguments.of(Map.of(
                "traceId", "trace-1",
                "keyword", "ERROR",
                "service", "order-service",
                "startTime", "2026-06-11T00:00:00Z",
                "endTime", "2026-06-11T01:00:00Z",
                "level", "ERROR",
                "limit", 50)), ctx);

        assertThat(result.success()).isTrue();
        assertThat(result.content()).isEqualTo("line-1");
        assertThat(client.lastRequest.traceId()).isEqualTo("trace-1");
        assertThat(client.lastRequest.keyword()).isEqualTo("ERROR");
        assertThat(client.lastRequest.service()).isEqualTo("order-service");
        assertThat(client.lastRequest.limit()).isEqualTo(50);
    }

    @Test
    void rejectsUnboundedQuery() {
        RecordingLogQueryClient client = RecordingLogQueryClient.returning("line-1");
        LogQueryTool tool = new LogQueryTool(client);

        ToolResult result = tool.execute(ToolArguments.of(Map.of("service", "order-service")), ctx);

        assertThat(result.success()).isFalse();
        assertThat(result.content()).contains("traceId or keyword");
        assertThat(client.calls).isZero();
    }

    @Test
    void backendFailureReturnsError() {
        RecordingLogQueryClient client = RecordingLogQueryClient.throwing(new IOException("log backend down"));
        LogQueryTool tool = new LogQueryTool(client);

        ToolResult result = tool.execute(ToolArguments.of(Map.of("traceId", "trace-1")), ctx);

        assertThat(result.success()).isFalse();
        assertThat(result.content()).contains("log backend down");
    }

    private static final class RecordingLogQueryClient implements LogQueryClient {

        private final String response;
        private final IOException failure;
        private int calls;
        private LogQueryRequest lastRequest;

        private RecordingLogQueryClient(String response, IOException failure) {
            this.response = response;
            this.failure = failure;
        }

        static RecordingLogQueryClient returning(String response) {
            return new RecordingLogQueryClient(response, null);
        }

        static RecordingLogQueryClient throwing(IOException failure) {
            return new RecordingLogQueryClient("", failure);
        }

        @Override
        public String query(LogQueryRequest request) throws IOException {
            calls++;
            lastRequest = request;
            if (failure != null) {
                throw failure;
            }
            return response;
        }
    }
}
