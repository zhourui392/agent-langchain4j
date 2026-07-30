package com.anthropic.agentkit.infrastructure.tools;

import com.anthropic.agentkit.domain.tool.ExecutionContext;
import com.anthropic.agentkit.domain.tool.ToolArguments;
import com.anthropic.agentkit.domain.tool.ToolResult;
import com.anthropic.agentkit.domain.tool.ToolResultStatus;
import com.anthropic.agentkit.domain.diagnosis.DiagnosisToolMetadata;
import com.anthropic.agentkit.infrastructure.tools.support.BackendErrorCode;
import com.anthropic.agentkit.infrastructure.tools.support.BackendFailure;
import com.anthropic.agentkit.infrastructure.tools.support.BackendQueryException;
import com.anthropic.agentkit.infrastructure.tools.support.LogQueryClient;
import com.anthropic.agentkit.infrastructure.tools.support.LogQueryRequest;
import com.anthropic.agentkit.infrastructure.tools.support.ScopedLogQueryClient;
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
        assertThat(result.metadata())
                .containsEntry(DiagnosisToolMetadata.DATA_SOURCE_ID, "unknown")
                .containsEntry(DiagnosisToolMetadata.ENVIRONMENT, "unknown")
                .containsEntry(DiagnosisToolMetadata.SERVICE, "order-service")
                .containsEntry(DiagnosisToolMetadata.QUERY_START, "2026-06-11T00:00:00Z")
                .containsEntry(DiagnosisToolMetadata.QUERY_END, "2026-06-11T01:00:00Z")
                .containsEntry(DiagnosisToolMetadata.BACKEND_STATUS, "SUCCEEDED")
                .containsEntry(DiagnosisToolMetadata.ERROR_CODE, "")
                .containsEntry(DiagnosisToolMetadata.RETRY_COUNT, "0");
    }

    @Test
    void rejectsUnboundedQuery() {
        RecordingLogQueryClient client = RecordingLogQueryClient.returning("line-1");
        LogQueryTool tool = new LogQueryTool(client);

        ToolResult result = tool.execute(ToolArguments.of(Map.of("service", "order-service")), ctx);

        assertThat(result.success()).isFalse();
        assertThat(result.content()).contains("traceId, keyword, or level");
        assertThat(client.calls).isZero();
    }

    @Test
    void backendFailureReturnsError() {
        RecordingLogQueryClient client = RecordingLogQueryClient.throwing(new IOException("log backend down"));
        LogQueryTool tool = new LogQueryTool(client);

        ToolResult result = tool.execute(ToolArguments.of(Map.of(
                "traceId", "trace-1", "service", "orders",
                "startTime", "2026-06-11T00:00:00Z",
                "endTime", "2026-06-11T01:00:00Z")), ctx);

        assertThat(result.success()).isFalse();
        assertThat(result.content()).contains("backend request could not be completed")
                .doesNotContain("log backend down");
    }

    @Test
    void scopedBackend_shouldPublishStableSuccessMetadataWithoutDuplicatingItInBody() {
        RecordingLogQueryClient delegate = RecordingLogQueryClient.returning("line-1\nline-2");
        LogQueryTool tool = new LogQueryTool(new ScopedLogQueryClient(
                delegate, "orders-prod-logs", "prod"));

        ToolResult result = tool.execute(ToolArguments.of(Map.of(
                "keyword", "ERROR", "service", "orders",
                "startTime", "2026-06-11T00:00:00Z",
                "endTime", "2026-06-11T01:00:00Z", "limit", 50)), ctx);

        assertThat(result.status()).isEqualTo(ToolResultStatus.SUCCESS);
        assertThat(result.content()).isEqualTo("line-1\nline-2");
        assertThat(result.metadata())
                .containsEntry(DiagnosisToolMetadata.DATA_SOURCE_ID, "orders-prod-logs")
                .containsEntry(DiagnosisToolMetadata.ENVIRONMENT, "prod")
                .containsEntry(DiagnosisToolMetadata.MATCHED, "2")
                .containsEntry(DiagnosisToolMetadata.RETURNED, "2")
                .containsEntry(DiagnosisToolMetadata.TRUNCATED, "false")
                .containsKey(DiagnosisToolMetadata.DURATION_MS);
        assertThat(result.content()).doesNotContain("dataSourceId=", "matched=");
    }

    @Test
    void typedBackendFailure_shouldPublishSafeFailureMetadata() {
        BackendQueryException failure = new BackendQueryException(new BackendFailure(
                BackendErrorCode.RATE_LIMITED, true, "backend rate limit reached"));
        LogQueryTool tool = new LogQueryTool(new ScopedLogQueryClient(
                RecordingLogQueryClient.throwing(failure), "orders-prod-logs", "prod"));

        ToolResult result = tool.execute(ToolArguments.of(Map.of(
                "traceId", "trace-1", "service", "orders",
                "startTime", "2026-06-11T00:00:00Z",
                "endTime", "2026-06-11T01:00:00Z")), ctx);

        assertThat(result.status()).isEqualTo(ToolResultStatus.ERROR);
        assertThat(result.metadata())
                .containsEntry(DiagnosisToolMetadata.DATA_SOURCE_ID, "orders-prod-logs")
                .containsEntry(DiagnosisToolMetadata.ENVIRONMENT, "prod")
                .containsEntry(DiagnosisToolMetadata.BACKEND_STATUS, "FAILED")
                .containsEntry(DiagnosisToolMetadata.ERROR_CODE, "RATE_LIMITED")
                .containsEntry(DiagnosisToolMetadata.RETRY_COUNT, "0");
    }

    @Test
    void rejectsMissingOrExcessiveTimeWindowAndScopedServiceMismatch() {
        LogQueryTool tool = new LogQueryTool(new ScopedLogQueryClient(
                RecordingLogQueryClient.returning("unused"),
                "orders-prod-logs", "prod", "orders"));

        assertThat(tool.execute(ToolArguments.of(Map.of(
                "keyword", "ERROR", "service", "orders")), ctx).success()).isFalse();
        assertThat(tool.execute(ToolArguments.of(Map.of(
                "keyword", "ERROR", "service", "orders",
                "startTime", "2026-06-01T00:00:00Z",
                "endTime", "2026-06-11T00:00:00Z")), ctx).success()).isFalse();
        assertThat(tool.execute(ToolArguments.of(Map.of(
                "keyword", "ERROR", "service", "payments",
                "startTime", "2026-06-11T00:00:00Z",
                "endTime", "2026-06-11T01:00:00Z")), ctx).success()).isFalse();
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
