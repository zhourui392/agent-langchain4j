package com.anthropic.agentkit.infrastructure.tools.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
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
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * Elasticsearch log adapter whose index and DSL shape are fixed by the host.
 *
 * @author alex
 * @since 2026-07-30
 */
public final class ElasticsearchLogQueryClient implements LogQueryClient {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern INDEX = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._*-]{0,254}");
    private static final Pattern FIELD = Pattern.compile("[A-Za-z_@][A-Za-z0-9_.@-]{0,127}");

    private final String baseUrl;
    private final Binding binding;
    private final Map<String, String> headers;
    private final Options options;
    private final HttpClient client;

    public ElasticsearchLogQueryClient(String baseUrl, Binding binding,
                                       Map<String, String> headers) {
        this(baseUrl, binding, headers, Options.defaults(), safeClient(Options.defaults()));
    }

    public ElasticsearchLogQueryClient(String baseUrl, Binding binding,
                                       Map<String, String> headers, Options options,
                                       HttpClient client) {
        this.baseUrl = requireBaseUrl(baseUrl);
        this.binding = Objects.requireNonNull(binding, "binding");
        this.headers = headers == null ? Map.of() : Map.copyOf(headers);
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
        HttpRequest.Builder builder = HttpRequest.newBuilder(endpoint(limit))
                .timeout(options.timeout())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                        queryBody(request, window, limit), StandardCharsets.UTF_8));
        headers.forEach(builder::header);
        JsonNode response = BoundedJsonHttpTransport.send(
                client, builder.build(), options.maxBodyBytes());
        return render(response, limit);
    }

    private String queryBody(LogQueryRequest request, QueryWindow window, int limit) {
        ObjectNode root = JSON.createObjectNode();
        ObjectNode bool = root.putObject("query").putObject("bool");
        ArrayNode filters = bool.putArray("filter");
        ObjectNode bounds = filters.addObject().putObject("range")
                .putObject(binding.timestampField());
        bounds.put("gte", window.start().toString()).put("lt", window.end().toString());
        binding.fixedTermFilters().forEach((field, value) -> addTerm(filters, field, value));
        addOptionalTerm(filters, binding.serviceField(), request.service());
        addOptionalTerm(filters, binding.levelField(), request.level());
        addOptionalTerm(filters, binding.traceField(), request.traceId());
        if (!request.keyword().isBlank()) {
            bool.putArray("must").addObject().putObject("match_phrase")
                    .put(binding.messageField(), request.keyword());
        }
        root.put("size", limit);
        root.putArray("_source").add(binding.timestampField()).add(binding.serviceField())
                .add(binding.levelField()).add(binding.messageField()).add(binding.traceField());
        root.putArray("sort").addObject().putObject(binding.timestampField()).put("order", "desc");
        return root.toString();
    }

    private void addOptionalTerm(ArrayNode filters, String field, String value) {
        if (!value.isBlank()) {
            addTerm(filters, field, value);
        }
    }

    private void addTerm(ArrayNode filters, String field, String value) {
        filters.addObject().putObject("term").put(field, value);
    }

    private LogQueryResult render(JsonNode root, int limit) throws BackendQueryException {
        try {
            JsonNode hits = required(required(root, "hits"), "hits");
            long matched = total(required(root, "hits"));
            if (!hits.isArray()) {
                throw new IllegalArgumentException("hits");
            }
            List<String> entries = new ArrayList<>();
            for (JsonNode hit : hits) {
                if (entries.size() == limit) {
                    break;
                }
                JsonNode source = required(hit, "_source");
                if (!source.isObject()) {
                    throw new IllegalArgumentException("_source");
                }
                entries.add(source.toString());
            }
            boolean truncated = matched > entries.size() || hits.size() > entries.size();
            return LogQueryResult.success(String.join("\n", entries), "es", "unknown",
                    "unknown", matched, entries.size(), truncated);
        } catch (IllegalArgumentException exception) {
            throw BoundedJsonHttpTransport.failure(BackendErrorCode.PROTOCOL_ERROR, false,
                    "Elasticsearch response contract is invalid");
        }
    }

    private long total(JsonNode hitsRoot) {
        JsonNode total = required(hitsRoot, "total");
        JsonNode value = total.isObject() ? required(total, "value") : total;
        if (!value.isIntegralNumber() || value.longValue() < 0L) {
            throw new IllegalArgumentException("total");
        }
        return value.longValue();
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
                    "Elasticsearch log query requires an absolute time window");
        }
    }

    private URI endpoint(int limit) {
        return URI.create(baseUrl + "/" + encodePath(binding.indexPattern())
                + "/_search?size=" + limit);
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

    private static String encodePath(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("%2F", "/");
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

    /** Host-owned ES index and field mapping. */
    public record Binding(String indexPattern, String timestampField, String serviceField,
                          String levelField, String messageField, String traceField,
                          Map<String, String> fixedTermFilters) {
        public Binding {
            indexPattern = valid(INDEX, indexPattern, "indexPattern");
            timestampField = valid(FIELD, timestampField, "timestampField");
            serviceField = valid(FIELD, serviceField, "serviceField");
            levelField = valid(FIELD, levelField, "levelField");
            messageField = valid(FIELD, messageField, "messageField");
            traceField = valid(FIELD, traceField, "traceField");
            Map<String, String> source = fixedTermFilters == null ? Map.of() : fixedTermFilters;
            TreeMap<String, String> checked = new TreeMap<>();
            source.forEach((field, value) -> checked.put(
                    valid(FIELD, field, "fixed filter field"), bounded(value, "fixed filter value")));
            fixedTermFilters = java.util.Collections.unmodifiableMap(
                    new java.util.LinkedHashMap<>(checked));
        }
    }

    /** Host-owned ES transport and result bounds. */
    public record Options(Duration timeout, int maxBodyBytes, int maxResults) {
        public Options {
            Objects.requireNonNull(timeout, "timeout");
            if (timeout.isZero() || timeout.isNegative() || maxBodyBytes <= 0
                    || maxBodyBytes == Integer.MAX_VALUE || maxResults <= 0) {
                throw new IllegalArgumentException("ES transport limits must be positive and bounded");
            }
        }

        public static Options defaults() {
            return new Options(Duration.ofSeconds(30), 1024 * 1024, 500);
        }
    }

    private static String valid(Pattern pattern, String value, String name) {
        String checked = bounded(value, name);
        if (!pattern.matcher(checked).matches() || checked.contains("..")) {
            throw new IllegalArgumentException(name + " is invalid");
        }
        return checked;
    }

    private static String bounded(String value, String name) {
        String checked = Objects.requireNonNull(value, name).trim();
        if (checked.isEmpty() || checked.length() > 512 || checked.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(name + " is invalid");
        }
        return checked;
    }

    private record QueryWindow(Instant start, Instant end) {
    }
}
