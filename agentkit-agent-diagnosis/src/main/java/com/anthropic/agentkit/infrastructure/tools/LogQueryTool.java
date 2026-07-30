package com.anthropic.agentkit.infrastructure.tools;

import com.anthropic.agentkit.domain.tool.ExecutionContext;
import com.anthropic.agentkit.domain.tool.Tool;
import com.anthropic.agentkit.domain.tool.ToolArguments;
import com.anthropic.agentkit.domain.tool.ToolResult;
import com.anthropic.agentkit.domain.tool.ToolResultStatus;
import com.anthropic.agentkit.domain.diagnosis.DiagnosisToolMetadata;
import com.anthropic.agentkit.infrastructure.tools.support.BackendErrorCode;
import com.anthropic.agentkit.infrastructure.tools.support.BackendQueryException;
import com.anthropic.agentkit.infrastructure.tools.support.LogSanitizer;
import com.anthropic.agentkit.infrastructure.tools.support.LogQueryClient;
import com.anthropic.agentkit.infrastructure.tools.support.LogQueryRequest;
import com.anthropic.agentkit.infrastructure.tools.support.LogQueryResult;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Read-only log search tool for incident diagnosis.
 *
 * @author zhourui(V33215020)
 * @since 2026-06-11
 */
public final class LogQueryTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(LogQueryTool.class);

    private static final int DEFAULT_LIMIT = 100;
    private static final int MAX_LIMIT = 500;
    private static final Duration MAX_TIME_WINDOW = Duration.ofHours(24);

    private final LogQueryClient client;

    public LogQueryTool(LogQueryClient client) {
        this.client = Objects.requireNonNull(client, "client");
    }

    @Override
    public String name() {
        return "LogQuery";
    }

    @Override
    public String description() {
        return "Read-only log search by traceId or keyword with optional service and time window scope.";
    }

    @Override
    public String inputSchema() {
        return "{\"type\":\"object\",\"properties\":{"
                + "\"traceId\":{\"type\":\"string\"},"
                + "\"keyword\":{\"type\":\"string\"},"
                + "\"service\":{\"type\":\"string\"},"
                + "\"startTime\":{\"type\":\"string\"},"
                + "\"endTime\":{\"type\":\"string\"},"
                + "\"level\":{\"type\":\"string\"},"
                + "\"limit\":{\"type\":\"integer\"}}}";
    }

    @Override
    public boolean isReadOnly() {
        return true;
    }

    @Override
    public ToolResult execute(ToolArguments args, ExecutionContext ctx) {
        long startNs = System.nanoTime();
        LogQueryRequest request = request(args);
        log.debug("log query args: traceIdPresent={}, keywordChars={}, service={}, startTime={}, endTime={}, level={}, limit={}",
                !request.traceId().isBlank(), request.keyword().length(), request.service(),
                request.startTime(), request.endTime(), request.level(), request.limit());
        if (!request.hasQueryAnchor()) {
            log.warn("log query blocked: reason=missing_query_anchor");
            return failure(request, startNs, BackendErrorCode.INVALID_QUERY,
                    "LogQuery requires traceId, keyword, or level");
        }
        String scopeFailure = scopeFailure(request);
        if (scopeFailure != null) {
            log.warn("log query blocked: reason=invalid_scope");
            return failure(request, startNs, BackendErrorCode.INVALID_QUERY, scopeFailure);
        }
        try {
            LogQueryResult output = client.queryResult(request);
            log.info("log query completed: service={}, lines={}, chars={}, durationMs={}",
                    request.service(), lineCount(output.content()), output.content().length(),
                    elapsedMs(startNs));
            return ToolResult.of(ToolResultStatus.SUCCESS, output.content(),
                    metadata(request, output, elapsedMs(startNs)));
        } catch (BackendQueryException ex) {
            log.warn("log query failed: service={}, errorCode={}, retryable={}",
                    request.service(), ex.failure().code(), ex.failure().retryable());
            return failure(request, startNs, ex.failure().code(),
                    ex.retryCount(), ex.getMessage());
        } catch (IOException ex) {
            log.error("log query failed: service={}, keyword={}, failureType={}",
                    request.service(), LogSanitizer.truncate(request.keyword(), 64),
                    ex.getClass().getSimpleName());
            return failure(request, startNs, BackendErrorCode.UNKNOWN, 0,
                    "LogQuery failed: backend request could not be completed");
        }
    }

    private Map<String, String> metadata(LogQueryRequest request, LogQueryResult result,
                                         long durationMs) {
        Map<String, String> metadata = baseMetadata(request, durationMs);
        metadata.put(DiagnosisToolMetadata.DATA_SOURCE_ID, result.dataSourceId());
        metadata.put(DiagnosisToolMetadata.ENVIRONMENT, result.environment());
        metadata.put(DiagnosisToolMetadata.MATCHED, Long.toString(result.matched()));
        metadata.put(DiagnosisToolMetadata.RETURNED, Integer.toString(result.returned()));
        metadata.put(DiagnosisToolMetadata.TRUNCATED, Boolean.toString(result.truncated()));
        metadata.put(DiagnosisToolMetadata.BACKEND_STATUS, result.backendStatus());
        metadata.put(DiagnosisToolMetadata.ERROR_CODE, result.errorCode());
        metadata.put(DiagnosisToolMetadata.RETRY_COUNT, Integer.toString(result.retryCount()));
        return Map.copyOf(metadata);
    }

    private ToolResult failure(LogQueryRequest request, long startNs,
                               BackendErrorCode errorCode, String message) {
        return failure(request, startNs, errorCode, 0, message);
    }

    private ToolResult failure(LogQueryRequest request, long startNs,
                               BackendErrorCode errorCode, int retryCount, String message) {
        Map<String, String> metadata = baseMetadata(request, elapsedMs(startNs));
        metadata.put(DiagnosisToolMetadata.DATA_SOURCE_ID, client.dataSourceId());
        metadata.put(DiagnosisToolMetadata.ENVIRONMENT, client.environment());
        metadata.put(DiagnosisToolMetadata.MATCHED, "0");
        metadata.put(DiagnosisToolMetadata.RETURNED, "0");
        metadata.put(DiagnosisToolMetadata.TRUNCATED, "false");
        metadata.put(DiagnosisToolMetadata.BACKEND_STATUS, "FAILED");
        metadata.put(DiagnosisToolMetadata.ERROR_CODE, errorCode.name());
        metadata.put(DiagnosisToolMetadata.RETRY_COUNT, Integer.toString(retryCount));
        return ToolResult.of(ToolResultStatus.ERROR, message, metadata);
    }

    private Map<String, String> baseMetadata(LogQueryRequest request, long durationMs) {
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put(DiagnosisToolMetadata.SERVICE, fallback(request.service()));
        metadata.put(DiagnosisToolMetadata.QUERY_START, request.startTime());
        metadata.put(DiagnosisToolMetadata.QUERY_END, request.endTime());
        metadata.put(DiagnosisToolMetadata.DURATION_MS, Long.toString(Math.max(0L, durationMs)));
        return metadata;
    }

    private String fallback(String value) {
        return value == null || value.isBlank() ? "unknown" : value;
    }

    private static LogQueryRequest request(ToolArguments args) {
        return new LogQueryRequest(
                args.getString("traceId", ""),
                args.getString("keyword", ""),
                args.getString("service", ""),
                args.getString("startTime", ""),
                args.getString("endTime", ""),
                args.getString("level", ""),
                Math.min(Math.max(1, args.getInt("limit", DEFAULT_LIMIT)), MAX_LIMIT));
    }

    private String scopeFailure(LogQueryRequest request) {
        if (request.service().isBlank()) {
            return "LogQuery requires a host-bound service";
        }
        if (!"unknown".equals(client.service())
                && !client.service().equals(request.service())) {
            return "LogQuery service is outside the host binding";
        }
        try {
            Instant start = Instant.parse(request.startTime());
            Instant end = Instant.parse(request.endTime());
            if (!start.isBefore(end) || Duration.between(start, end).compareTo(MAX_TIME_WINDOW) > 0) {
                return "LogQuery absolute time window is invalid or exceeds 24 hours";
            }
            return null;
        } catch (DateTimeParseException failure) {
            return "LogQuery requires an absolute startTime and endTime";
        }
    }

    private static int lineCount(String output) {
        return output == null || output.isEmpty() ? 0 : output.split("\\R", -1).length;
    }

    private static long elapsedMs(long startNs) {
        return (System.nanoTime() - startNs) / 1_000_000L;
    }
}
