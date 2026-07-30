package com.anthropic.agentkit.infrastructure.tools.support;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.StringJoiner;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link LogQueryClient} backed by a host-provided, bounded HTTP endpoint.
 *
 * @author zhourui(V33215020)
 * @since 2026-06-13
 */
public final class HttpLogQueryClient implements LogQueryClient {

    private static final Logger log = LoggerFactory.getLogger(HttpLogQueryClient.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int DEFAULT_MAX_BODY_BYTES = 1_048_576;
    private static final int MAX_QUERY_ID_LENGTH = 256;

    private final String endpointUrl;
    private final Map<String, String> headers;
    private final Options options;
    private final HttpClient client;

    public HttpLogQueryClient(String endpointUrl, Map<String, String> headers) {
        this(endpointUrl, headers, Options.defaults());
    }

    public HttpLogQueryClient(
            String endpointUrl,
            Map<String, String> headers,
            Options options) {
        this(endpointUrl, headers, options, HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .build());
    }

    HttpLogQueryClient(
            String endpointUrl,
            Map<String, String> headers,
            Options options,
            HttpClient client) {
        this.endpointUrl = requireEndpoint(endpointUrl);
        this.headers = headers == null ? Map.of() : Map.copyOf(headers);
        this.options = Objects.requireNonNull(options, "options");
        this.client = Objects.requireNonNull(client, "client");
    }

    @Override
    public String query(LogQueryRequest request) throws IOException {
        LogQueryResult result = queryResult(request);
        return "legacy-http".equals(result.queryId())
                ? result.content() : result.legacyRender();
    }

    @Override
    public LogQueryResult queryResult(LogQueryRequest request) throws IOException {
        Objects.requireNonNull(request, "request");
        long startNs = System.nanoTime();
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri(request))
                .timeout(options.timeout())
                .GET();
        headers.forEach(builder::header);
        log.debug("log http query started: servicePresent={}, traceIdPresent={}, limit={}",
                !request.service().isBlank(), !request.traceId().isBlank(), request.limit());
        try {
            HttpResponse<InputStream> response = client.send(
                    builder.build(), HttpResponse.BodyHandlers.ofInputStream());
            LogQueryResult result = handle(response, request.limit());
            log.debug("log http query completed: status={}, durationMs={}",
                    response.statusCode(), elapsedMs(startNs));
            return result;
        } catch (HttpTimeoutException exception) {
            throw failure(BackendErrorCode.TIMED_OUT, true,
                    "backend request timed out", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw failure(BackendErrorCode.TIMED_OUT, true,
                    "backend request interrupted", exception);
        } catch (BackendQueryException exception) {
            throw exception;
        } catch (IOException exception) {
            throw failure(BackendErrorCode.CONNECTION_FAILED, true,
                    "backend connection failed", exception);
        }
    }

    private LogQueryResult handle(HttpResponse<InputStream> response, int requestLimit)
            throws IOException {
        try (InputStream body = response.body()) {
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw statusFailure(response.statusCode());
            }
            validateContentLength(response);
            byte[] bytes = readBounded(body);
            MediaType mediaType = mediaType(response);
            String content = decodeUtf8(bytes);
            if (mediaType.json()) {
                return typedResponse(content, requestLimit);
            }
            if (mediaType.legacyText() && options.legacyTextAllowed()) {
                int lines = content.isBlank() ? 0 : content.split("\\R", -1).length;
                return LogQueryResult.success(content, "legacy-http", "unknown", "unknown",
                        lines, lines, false);
            }
            throw protocolFailure("backend response content type is unsupported");
        }
    }

    private void validateContentLength(HttpResponse<?> response) throws BackendQueryException {
        long contentLength = response.headers()
                .firstValueAsLong("Content-Length")
                .orElse(-1L);
        if (contentLength > options.maxBodyBytes()) {
            throw responseTooLarge();
        }
    }

    private byte[] readBounded(InputStream body) throws IOException {
        byte[] bytes = body.readNBytes(options.maxBodyBytes() + 1);
        if (bytes.length > options.maxBodyBytes()) {
            throw responseTooLarge();
        }
        return bytes;
    }

    private MediaType mediaType(HttpResponse<?> response) throws BackendQueryException {
        String raw = response.headers().firstValue("Content-Type").orElse("");
        String[] parts = raw.split(";");
        String type = parts[0].trim().toLowerCase(Locale.ROOT);
        for (int index = 1; index < parts.length; index++) {
            validateCharset(parts[index]);
        }
        boolean json = type.equals("application/json")
                || type.startsWith("application/") && type.endsWith("+json");
        return new MediaType(json, type.equals("text/plain"));
    }

    private void validateCharset(String parameter) throws BackendQueryException {
        String[] pair = parameter.trim().split("=", 2);
        if (pair.length != 2 || !pair[0].trim().equalsIgnoreCase("charset")) {
            return;
        }
        String charset = pair[1].trim().replace("\"", "");
        if (!charset.equalsIgnoreCase(StandardCharsets.UTF_8.name())
                && !charset.equalsIgnoreCase("utf8")) {
            throw protocolFailure("backend response charset must be UTF-8");
        }
    }

    private LogQueryResult typedResponse(String content, int requestLimit)
            throws BackendQueryException {
        try {
            JsonNode root = MAPPER.readTree(content);
            TypedLogResponse response = typedResponse(root, requestLimit);
            return LogQueryResult.success(String.join("\n", response.entries()),
                    response.queryId(), "unknown", "unknown", response.matched(),
                    response.entries().size(), response.truncated());
        } catch (JsonProcessingException | IllegalArgumentException exception) {
            throw failure(BackendErrorCode.PROTOCOL_ERROR, false,
                    "backend response contract is invalid", exception);
        }
    }

    private TypedLogResponse typedResponse(JsonNode root, int requestLimit) {
        if (root == null || !root.isObject()) {
            throw new IllegalArgumentException("root");
        }
        JsonNode entriesNode = required(root, "entries");
        int matched = nonNegativeInt(required(root, "matched"));
        boolean truncated = requiredBoolean(root, "truncated");
        String queryId = boundedText(required(root, "queryId"));
        if (!entriesNode.isArray() || matched < entriesNode.size()) {
            throw new IllegalArgumentException("entries");
        }
        int returned = Math.min(entriesNode.size(), requestLimit);
        List<String> entries = renderEntries(entriesNode, returned);
        return new TypedLogResponse(queryId, matched, entries,
                truncated || returned < entriesNode.size());
    }

    private List<String> renderEntries(JsonNode entriesNode, int returned) {
        List<String> entries = new ArrayList<>(returned);
        for (int index = 0; index < returned; index++) {
            JsonNode entry = entriesNode.get(index);
            if (entry.isTextual()) {
                entries.add(entry.textValue());
            } else if (entry.isObject()) {
                entries.add(entry.toString());
            } else {
                throw new IllegalArgumentException("entry");
            }
        }
        return List.copyOf(entries);
    }

    private static JsonNode required(JsonNode root, String field) {
        JsonNode value = root.get(field);
        if (value == null || value.isNull()) {
            throw new IllegalArgumentException(field);
        }
        return value;
    }

    private static int nonNegativeInt(JsonNode value) {
        if (!value.isIntegralNumber() || !value.canConvertToInt() || value.intValue() < 0) {
            throw new IllegalArgumentException("non-negative integer");
        }
        return value.intValue();
    }

    private static boolean requiredBoolean(JsonNode root, String field) {
        JsonNode value = required(root, field);
        if (!value.isBoolean()) {
            throw new IllegalArgumentException(field);
        }
        return value.booleanValue();
    }

    private static String boundedText(JsonNode value) {
        if (!value.isTextual()) {
            throw new IllegalArgumentException("text");
        }
        String text = value.textValue().trim();
        if (text.isEmpty() || text.length() > MAX_QUERY_ID_LENGTH) {
            throw new IllegalArgumentException("bounded text");
        }
        return text;
    }

    private static String decodeUtf8(byte[] bytes) throws BackendQueryException {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException exception) {
            throw failure(BackendErrorCode.PROTOCOL_ERROR, false,
                    "backend response is not valid UTF-8", exception);
        }
    }

    private URI uri(LogQueryRequest request) {
        String separator = endpointUrl.contains("?") ? "&" : "?";
        return URI.create(endpointUrl + separator + queryString(parameters(request)));
    }

    private Map<String, String> parameters(LogQueryRequest request) {
        Map<String, String> params = new LinkedHashMap<>();
        putIfPresent(params, "traceId", request.traceId());
        putIfPresent(params, "keyword", request.keyword());
        putIfPresent(params, "service", request.service());
        putIfPresent(params, "startTime", request.startTime());
        putIfPresent(params, "endTime", request.endTime());
        putIfPresent(params, "level", request.level());
        params.put("limit", Integer.toString(request.limit()));
        return params;
    }

    private static void putIfPresent(Map<String, String> params, String name, String value) {
        if (!value.isBlank()) {
            params.put(name, value);
        }
    }

    private static String queryString(Map<String, String> params) {
        StringJoiner joiner = new StringJoiner("&");
        params.forEach((key, value) -> joiner.add(encode(key) + "=" + encode(value)));
        return joiner.toString();
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static BackendQueryException statusFailure(int status) {
        if (status == 401) {
            return statusFailure(status, BackendErrorCode.AUTHENTICATION_FAILED,
                    false, "backend authentication failed");
        }
        if (status == 403) {
            return statusFailure(status, BackendErrorCode.AUTHORIZATION_DENIED,
                    false, "backend authorization denied");
        }
        if (status == 408 || status == 504) {
            return statusFailure(status, BackendErrorCode.TIMED_OUT,
                    true, "backend request timed out");
        }
        if (status == 429) {
            return statusFailure(status, BackendErrorCode.RATE_LIMITED,
                    true, "backend rate limit reached");
        }
        if (status >= 500 && status <= 599) {
            return statusFailure(status, BackendErrorCode.UNAVAILABLE,
                    true, "backend is temporarily unavailable");
        }
        if (status >= 400 && status <= 499) {
            return statusFailure(status, BackendErrorCode.INVALID_QUERY,
                    false, "backend rejected the query");
        }
        return statusFailure(status, BackendErrorCode.PROTOCOL_ERROR,
                false, "backend returned an unexpected status");
    }

    private static BackendQueryException statusFailure(
            int status,
            BackendErrorCode code,
            boolean retryable,
            String safeMessage) {
        return new BackendQueryException(new BackendFailure(code, retryable, safeMessage), status);
    }

    private static BackendQueryException responseTooLarge() {
        return failure(BackendErrorCode.RESPONSE_TOO_LARGE, false,
                "backend response exceeded the configured limit", null);
    }

    private static BackendQueryException protocolFailure(String message) {
        return failure(BackendErrorCode.PROTOCOL_ERROR, false, message, null);
    }

    private static BackendQueryException failure(
            BackendErrorCode code,
            boolean retryable,
            String safeMessage,
            Throwable cause) {
        BackendFailure failure = new BackendFailure(code, retryable, safeMessage);
        return cause == null
                ? new BackendQueryException(failure)
                : new BackendQueryException(failure, cause);
    }

    private static String requireEndpoint(String endpointUrl) {
        String value = Objects.requireNonNull(endpointUrl, "endpointUrl").trim();
        URI uri = URI.create(value);
        if (value.isEmpty() || !("http".equalsIgnoreCase(uri.getScheme())
                || "https".equalsIgnoreCase(uri.getScheme()))
                || uri.getHost() == null || uri.getUserInfo() != null
                || uri.getFragment() != null) {
            throw new IllegalArgumentException("endpointUrl must be a safe absolute HTTP(S) URI");
        }
        return value;
    }

    private static long elapsedMs(long startNs) {
        return (System.nanoTime() - startNs) / 1_000_000L;
    }

    /** Safe, immutable HTTP response limits. */
    public record Options(Duration timeout, int maxBodyBytes, boolean legacyTextAllowed) {

        public Options {
            Objects.requireNonNull(timeout, "timeout");
            if (timeout.isZero() || timeout.isNegative()) {
                throw new IllegalArgumentException("timeout must be positive");
            }
            if (maxBodyBytes <= 0 || maxBodyBytes == Integer.MAX_VALUE) {
                throw new IllegalArgumentException("maxBodyBytes must be positive and bounded");
            }
        }

        public static Options defaults() {
            return new Options(Duration.ofSeconds(30), DEFAULT_MAX_BODY_BYTES, true);
        }
    }

    private record MediaType(boolean json, boolean legacyText) {
    }

    private record TypedLogResponse(
            String queryId,
            int matched,
            List<String> entries,
            boolean truncated) {

        private String render() {
            String header = "queryId=" + queryId
                    + " matched=" + matched
                    + " returned=" + entries.size()
                    + " truncated=" + truncated;
            return entries.isEmpty() ? header : header + "\n" + String.join("\n", entries);
        }
    }

}
