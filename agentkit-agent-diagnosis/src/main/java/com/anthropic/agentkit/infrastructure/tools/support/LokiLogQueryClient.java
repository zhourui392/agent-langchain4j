package com.anthropic.agentkit.infrastructure.tools.support;

import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.math.BigInteger;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.StringJoiner;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * Loki log adapter with a host-fixed tenant, selector, and label mapping.
 *
 * @author alex
 * @since 2026-07-30
 */
public final class LokiLogQueryClient implements LogQueryClient {

    private static final Pattern LABEL = Pattern.compile("[A-Za-z_][A-Za-z0-9_]{0,127}");
    private static final BigInteger BILLION = BigInteger.valueOf(1_000_000_000L);

    private final String baseUrl;
    private final Binding binding;
    private final Map<String, String> headers;
    private final Options options;
    private final HttpClient client;

    public LokiLogQueryClient(String baseUrl, Binding binding, Map<String, String> headers) {
        this(baseUrl, binding, headers, Options.defaults(), safeClient(Options.defaults()));
    }

    public LokiLogQueryClient(String baseUrl, Binding binding, Map<String, String> headers,
                              Options options, HttpClient client) {
        this.baseUrl = requireBaseUrl(baseUrl);
        this.binding = Objects.requireNonNull(binding, "binding");
        this.headers = safeHeaders(headers);
        this.options = Objects.requireNonNull(options, "options");
        this.client = requireSafeClient(client);
    }

    @Override
    public String query(LogQueryRequest request) throws IOException {
        return queryResult(request).legacyRender();
    }

    @Override
    public LogQueryResult queryResult(LogQueryRequest request) throws IOException {
        QueryWindow window = queryWindow(request);
        int limit = Math.min(request.limit(), options.maxResults());
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri(request, window, limit))
                .timeout(options.timeout()).GET();
        headers.forEach(builder::header);
        builder.setHeader("X-Scope-OrgID", binding.tenantId());
        JsonNode response = BoundedJsonHttpTransport.send(
                client, builder.build(), options.maxBodyBytes());
        return render(response, limit);
    }

    private URI uri(LogQueryRequest request, QueryWindow window, int limit)
            throws BackendQueryException {
        Map<String, String> parameters = new TreeMap<>();
        parameters.put("query", logQl(request));
        parameters.put("start", epochNanos(window.start()));
        parameters.put("end", epochNanos(window.end()));
        parameters.put("limit", Integer.toString(limit));
        parameters.put("direction", "backward");
        StringJoiner query = new StringJoiner("&");
        parameters.forEach((name, value) -> query.add(encode(name) + "=" + encode(value)));
        return URI.create(baseUrl + "/loki/api/v1/query_range?" + query);
    }

    private String logQl(LogQueryRequest request) throws BackendQueryException {
        StringJoiner selector = new StringJoiner(",", "{", "}");
        for (Map.Entry<String, String> entry : binding.baseSelector().entrySet()) {
            selector.add(entry.getKey() + "=\"" + escape(entry.getValue()) + "\"");
        }
        if (!request.level().isBlank()) {
            selector.add(binding.levelLabel() + "=\"" + escape(request.level()) + "\"");
        }
        if (!request.service().isBlank()) {
            selector.add(binding.serviceLabel() + "=\"" + escape(request.service()) + "\"");
        }
        StringBuilder query = new StringBuilder(selector.toString());
        appendContains(query, request.traceId());
        appendContains(query, request.keyword());
        return query.toString();
    }

    private void appendContains(StringBuilder query, String value) throws BackendQueryException {
        if (!value.isBlank()) {
            query.append(" |= \"").append(escape(value)).append('"');
        }
    }

    private String escape(String value) throws BackendQueryException {
        if (value.length() > 4096 || value.chars().anyMatch(Character::isISOControl)) {
            throw BoundedJsonHttpTransport.failure(BackendErrorCode.INVALID_QUERY, false,
                    "Loki logical query value is invalid");
        }
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private LogQueryResult render(JsonNode root, int limit) throws BackendQueryException {
        try {
            if (!"success".equals(required(root, "status").textValue())) {
                throw new IllegalArgumentException("status");
            }
            JsonNode data = required(root, "data");
            if (!"streams".equals(required(data, "resultType").textValue())) {
                throw new IllegalArgumentException("resultType");
            }
            JsonNode streams = required(data, "result");
            if (!streams.isArray()) {
                throw new IllegalArgumentException("result");
            }
            List<String> entries = new ArrayList<>();
            int matched = collect(streams, entries, limit);
            return LogQueryResult.success(String.join("\n", entries), "loki", "unknown",
                    "unknown", matched, entries.size(), matched > entries.size());
        } catch (IllegalArgumentException exception) {
            throw BoundedJsonHttpTransport.failure(BackendErrorCode.PROTOCOL_ERROR, false,
                    "Loki response contract is invalid");
        }
    }

    private int collect(JsonNode streams, List<String> entries, int limit) {
        int matched = 0;
        for (JsonNode stream : streams) {
            JsonNode values = required(stream, "values");
            if (!values.isArray()) {
                throw new IllegalArgumentException("values");
            }
            for (JsonNode value : values) {
                matched++;
                if (entries.size() < limit) {
                    entries.add(logLine(value));
                }
            }
        }
        return matched;
    }

    private String logLine(JsonNode value) {
        if (!value.isArray() || value.size() != 2
                || !value.get(0).isTextual() || !value.get(1).isTextual()) {
            throw new IllegalArgumentException("log value");
        }
        return value.get(0).textValue() + " " + value.get(1).textValue();
    }

    private JsonNode required(JsonNode root, String name) {
        JsonNode value = root == null ? null : root.get(name);
        if (value == null || value.isNull()) {
            throw new IllegalArgumentException(name);
        }
        return value;
    }

    private QueryWindow queryWindow(LogQueryRequest request) throws BackendQueryException {
        try {
            Instant start = Instant.parse(request.startTime());
            Instant end = Instant.parse(request.endTime());
            if (!start.isBefore(end)) {
                throw new DateTimeParseException("invalid order", request.startTime(), 0);
            }
            return new QueryWindow(start, end);
        } catch (DateTimeParseException exception) {
            throw BoundedJsonHttpTransport.failure(BackendErrorCode.INVALID_QUERY, false,
                    "Loki log query requires an absolute time window");
        }
    }

    private static String epochNanos(Instant instant) {
        return BigInteger.valueOf(instant.getEpochSecond()).multiply(BILLION)
                .add(BigInteger.valueOf(instant.getNano())).toString();
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String requireBaseUrl(String value) {
        String base = Objects.requireNonNull(value, "baseUrl").trim();
        URI uri = URI.create(base);
        if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                || uri.getHost() == null || uri.getUserInfo() != null
                || uri.getQuery() != null || uri.getFragment() != null) {
            throw new IllegalArgumentException("baseUrl must be a safe absolute HTTP(S) URI");
        }
        return base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
    }

    private static Map<String, String> safeHeaders(Map<String, String> headers) {
        Map<String, String> value = headers == null ? Map.of() : Map.copyOf(headers);
        if (value.keySet().stream().anyMatch("X-Scope-OrgID"::equalsIgnoreCase)) {
            throw new IllegalArgumentException("tenant header is owned by the Loki binding");
        }
        return value;
    }

    private static HttpClient safeClient(Options options) {
        return HttpClient.newBuilder().connectTimeout(options.timeout())
                .followRedirects(HttpClient.Redirect.NEVER).build();
    }

    private static HttpClient requireSafeClient(HttpClient client) {
        HttpClient value = Objects.requireNonNull(client, "client");
        if (value.followRedirects() != HttpClient.Redirect.NEVER) {
            throw new IllegalArgumentException("diagnosis backend redirects must be disabled");
        }
        return value;
    }

    /** Host-owned Loki tenant, base selector, and dynamic label names. */
    public record Binding(String tenantId, Map<String, String> baseSelector,
                          String serviceLabel, String levelLabel) {
        public Binding {
            tenantId = bounded(tenantId, "tenantId");
            serviceLabel = label(serviceLabel, "serviceLabel");
            levelLabel = label(levelLabel, "levelLabel");
            TreeMap<String, String> selector = new TreeMap<>();
            Map<String, String> source = baseSelector == null ? Map.of() : baseSelector;
            source.forEach((name, value) -> selector.put(
                    label(name, "base selector label"), bounded(value, "base selector value")));
            if (selector.containsKey(serviceLabel) || selector.containsKey(levelLabel)) {
                throw new IllegalArgumentException("base selector cannot replace dynamic labels");
            }
            baseSelector = java.util.Collections.unmodifiableMap(
                    new java.util.LinkedHashMap<>(selector));
        }
    }

    /** Host-owned Loki transport and result bounds. */
    public record Options(Duration timeout, int maxBodyBytes, int maxResults) {
        public Options {
            Objects.requireNonNull(timeout, "timeout");
            if (timeout.isZero() || timeout.isNegative() || maxBodyBytes <= 0
                    || maxBodyBytes == Integer.MAX_VALUE || maxResults <= 0) {
                throw new IllegalArgumentException("Loki transport limits must be positive and bounded");
            }
        }

        public static Options defaults() {
            return new Options(Duration.ofSeconds(30), 1024 * 1024, 500);
        }
    }

    private static String label(String value, String name) {
        String checked = bounded(value, name);
        if (!LABEL.matcher(checked).matches()) {
            throw new IllegalArgumentException(name + " is invalid");
        }
        return checked;
    }

    private static String bounded(String value, String name) {
        String checked = Objects.requireNonNull(value, name).trim();
        if (checked.isEmpty() || checked.length() > 512
                || checked.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(name + " is invalid");
        }
        return checked;
    }

    private record QueryWindow(Instant start, Instant end) {
    }
}
