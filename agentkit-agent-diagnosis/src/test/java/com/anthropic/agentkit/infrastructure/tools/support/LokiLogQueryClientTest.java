package com.anthropic.agentkit.infrastructure.tools.support;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * @author alex
 */
class LokiLogQueryClientTest {

    private HttpServer server;
    private URI capturedUri;
    private String capturedTenant;
    private String capturedAuthorization;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void buildsBoundedQueryRangeFromLogicalParametersAndFixedBinding() throws Exception {
        start(exchange -> respond(exchange, 200, """
                {"status":"success","data":{"resultType":"streams","result":[
                  {"stream":{"service":"agent-web"},"values":[
                    ["1785373200000000000","first error"],
                    ["1785373260000000000","second error"]]}]}}
                """));

        String result = client().query(request("error \"quoted\""));
        Map<String, String> query = decodedQuery(capturedUri);

        assertThat(capturedUri.getPath()).isEqualTo("/loki/api/v1/query_range");
        assertThat(capturedTenant).isEqualTo("tenant-prod");
        assertThat(capturedAuthorization).isEqualTo("Bearer host-secret");
        assertThat(query.get("query"))
                .startsWith("{cluster=\"prod-a\",namespace=\"payments\",level=\"ERROR\",service=\"agent-web\"}")
                .contains("|= \"trace-1\"", "|= \"error \\\"quoted\\\"\"");
        assertThat(query).containsEntry("start", "1785369600000000000")
                .containsEntry("end", "1785376800000000000")
                .containsEntry("limit", "20")
                .containsEntry("direction", "backward");
        assertThat(result)
                .contains("queryId=loki matched=2 returned=2 truncated=false",
                        "first error", "second error")
                .doesNotContain("tenant-prod", "host-secret", endpoint());
    }

    @Test
    void logicalParametersCannotOverrideTenantOrBaseSelector() throws Exception {
        start(exchange -> respond(exchange, 200,
                "{\"status\":\"success\",\"data\":{\"resultType\":\"streams\",\"result\":[]}}"));
        String injected = "x\"} |= \"all secrets";

        client().query(request(injected));

        String logQl = decodedQuery(capturedUri).get("query");
        assertThat(capturedTenant).isEqualTo("tenant-prod");
        assertThat(logQl).startsWith(
                "{cluster=\"prod-a\",namespace=\"payments\",level=\"ERROR\",service=\"agent-web\"}");
        assertThat(logQl).endsWith("|= \"x\\\"} |= \\\"all secrets\"");
    }

    @Test
    void requiresAbsoluteTimeWindowAndMapsLokiErrors() throws Exception {
        start(exchange -> respond(exchange, 200,
                "{\"status\":\"error\",\"errorType\":\"bad_data\",\"error\":\"host-secret\"}"));

        assertThatThrownBy(() -> client().query(request("error")))
                .isInstanceOfSatisfying(BackendQueryException.class, failure -> {
                    assertThat(failure.failure().code()).isEqualTo(BackendErrorCode.PROTOCOL_ERROR);
                    assertThat(failure.getMessage()).doesNotContain("host-secret", endpoint());
                });
        LogQueryRequest missingTime = new LogQueryRequest(
                "trace-1", "error", "agent-web", "", "", "ERROR", 20);
        assertThatThrownBy(() -> client().query(missingTime))
                .isInstanceOfSatisfying(BackendQueryException.class,
                        failure -> assertThat(failure.failure().code())
                                .isEqualTo(BackendErrorCode.INVALID_QUERY));
    }

    @Test
    void mapsNonSuccessWithoutReadingSensitiveErrorBody() throws Exception {
        start(exchange -> respond(exchange, 429, "host-secret should not escape"));

        assertThatThrownBy(() -> client().query(request("error")))
                .isInstanceOfSatisfying(BackendQueryException.class, failure -> {
                    assertThat(failure.failure().code()).isEqualTo(BackendErrorCode.RATE_LIMITED);
                    assertThat(failure.failure().retryable()).isTrue();
                    assertThat(failure.getMessage()).doesNotContain("host-secret", endpoint());
                });
    }

    private LokiLogQueryClient client() {
        LokiLogQueryClient.Binding binding = new LokiLogQueryClient.Binding(
                "tenant-prod", Map.of("cluster", "prod-a", "namespace", "payments"),
                "service", "level");
        LokiLogQueryClient.Options options = new LokiLogQueryClient.Options(
                Duration.ofSeconds(2), 64 * 1024, 100);
        return new LokiLogQueryClient(endpoint(), binding,
                Map.of("Authorization", "Bearer host-secret"), options,
                HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build());
    }

    private LogQueryRequest request(String keyword) {
        return new LogQueryRequest("trace-1", keyword, "agent-web",
                "2026-07-30T00:00:00Z", "2026-07-30T02:00:00Z", "ERROR", 20);
    }

    private void start(ExchangeHandler handler) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            capturedUri = exchange.getRequestURI();
            capturedTenant = exchange.getRequestHeaders().getFirst("X-Scope-OrgID");
            capturedAuthorization = exchange.getRequestHeaders().getFirst("Authorization");
            handler.handle(exchange);
        });
        server.start();
    }

    private void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private Map<String, String> decodedQuery(URI uri) {
        Map<String, String> values = new LinkedHashMap<>();
        Arrays.stream(uri.getRawQuery().split("&")).forEach(pair -> {
            String[] parts = pair.split("=", 2);
            values.put(URLDecoder.decode(parts[0], StandardCharsets.UTF_8),
                    URLDecoder.decode(parts[1], StandardCharsets.UTF_8));
        });
        return values;
    }

    private String endpoint() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @FunctionalInterface
    private interface ExchangeHandler {
        void handle(HttpExchange exchange) throws IOException;
    }
}
