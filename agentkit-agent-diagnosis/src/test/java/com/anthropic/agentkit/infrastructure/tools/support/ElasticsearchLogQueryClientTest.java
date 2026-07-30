package com.anthropic.agentkit.infrastructure.tools.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * @author alex
 */
class ElasticsearchLogQueryClientTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private HttpServer server;
    private URI capturedUri;
    private JsonNode capturedBody;
    private String capturedAuthorization;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void buildsBoundedDslFromLogicalParametersAndFixedHostBinding() throws Exception {
        start(exchange -> respond(exchange, 200, """
                {"took":7,"timed_out":false,"hits":{"total":{"value":2,"relation":"eq"},
                 "hits":[{"_source":{"@timestamp":"2026-07-30T01:00:00Z","message":"boom"}},
                         {"_source":{"@timestamp":"2026-07-30T01:01:00Z","message":"failed"}}]}}
                """));
        ElasticsearchLogQueryClient client = client();

        String result = client.query(request("error \"quoted\""));

        assertThat(capturedUri.getRawPath()).isEqualTo("/logs-*/_search");
        assertThat(capturedUri.getRawQuery()).isEqualTo("size=20");
        assertThat(capturedAuthorization).isEqualTo("ApiKey host-secret");
        assertThat(capturedBody.at("/query/bool/filter/0/range/@timestamp/gte").asText())
                .isEqualTo("2026-07-30T00:00:00Z");
        assertThat(capturedBody.at("/query/bool/filter/0/range/@timestamp/lt").asText())
                .isEqualTo("2026-07-30T02:00:00Z");
        assertThat(capturedBody.toString())
                .contains("agent-web", "ERROR", "trace-1", "error \\\"quoted\\\"")
                .doesNotContain("script");
        assertThat(result)
                .contains("queryId=es matched=2 returned=2 truncated=false", "boom", "failed")
                .doesNotContain("host-secret", endpoint());
    }

    @Test
    void queryTextCannotOverrideIndexEndpointOrInjectDsl() throws Exception {
        start(exchange -> respond(exchange, 200, """
                {"took":1,"timed_out":false,"hits":{"total":{"value":0,"relation":"eq"},"hits":[]}}
                """));
        String injected = "x\"},\"script\":{\"source\":\"danger\"},\"index\":\"secret-*";

        client().query(request(injected));

        assertThat(capturedUri.getRawPath()).isEqualTo("/logs-*/_search");
        assertThat(capturedBody.findValue("script")).isNull();
        assertThat(capturedBody.findValue("index")).isNull();
        assertThat(capturedBody.findValue("match_phrase").get("message").asText())
                .isEqualTo(injected);
    }

    @Test
    void mapsNonSuccessToSanitizedTypedFailure() throws Exception {
        start(exchange -> respond(exchange, 403,
                "{\"error\":\"host-secret cannot access secret-index\"}"));

        assertThatThrownBy(() -> client().query(request("error")))
                .isInstanceOfSatisfying(BackendQueryException.class, failure -> {
                    assertThat(failure.failure().code())
                            .isEqualTo(BackendErrorCode.AUTHORIZATION_DENIED);
                    assertThat(failure.failure().retryable()).isFalse();
                    assertThat(failure.getMessage())
                            .doesNotContain("host-secret", "secret-index", endpoint());
                });
    }

    @Test
    void validatesHostOwnedIndexAndFieldConfiguration() {
        assertThatThrownBy(() -> new ElasticsearchLogQueryClient.Binding(
                "../secret", "@timestamp", "service", "level", "message", "trace.id", Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ElasticsearchLogQueryClient.Binding(
                "logs-*", "@timestamp", "service", "level", "message", "trace.id",
                Map.of("bad field;script", "x")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private ElasticsearchLogQueryClient client() {
        ElasticsearchLogQueryClient.Binding binding = new ElasticsearchLogQueryClient.Binding(
                "logs-*", "@timestamp", "service.name", "log.level", "message", "trace.id",
                Map.of("cluster", "prod-a"));
        ElasticsearchLogQueryClient.Options options = new ElasticsearchLogQueryClient.Options(
                Duration.ofSeconds(2), 64 * 1024, 100);
        return new ElasticsearchLogQueryClient(endpoint(), binding,
                Map.of("Authorization", "ApiKey host-secret"), options,
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
            capturedAuthorization = exchange.getRequestHeaders().getFirst("Authorization");
            byte[] requestBody = exchange.getRequestBody().readAllBytes();
            capturedBody = requestBody.length == 0 ? null : JSON.readTree(requestBody);
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

    private String endpoint() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @FunctionalInterface
    private interface ExchangeHandler {
        void handle(HttpExchange exchange) throws IOException;
    }
}
