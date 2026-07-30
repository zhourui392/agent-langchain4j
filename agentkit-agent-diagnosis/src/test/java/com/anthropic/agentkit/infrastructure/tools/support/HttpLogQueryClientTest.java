package com.anthropic.agentkit.infrastructure.tools.support;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * @author alex
 */
class HttpLogQueryClientTest {

    private HttpServer server;
    private String capturedQuery;
    private String capturedAuthorization;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void parsesTypedJsonContractAndEncodesLogicalQueryParameters() throws Exception {
        start(exchange -> respond(exchange, 200, "application/json; charset=UTF-8", """
                {"entries":["first",{"timestamp":"2026-07-30T01:00:00Z","message":"second"}],
                 "matched":3,"truncated":true,"queryId":"query-1"}
                """));
        HttpLogQueryClient client = new HttpLogQueryClient(
                endpoint(), Map.of("Authorization", "Bearer test-secret"));

        String result = client.query(request());

        assertThat(result).contains(
                "queryId=query-1 matched=3 returned=2 truncated=true",
                "first", "\"message\":\"second\"");
        assertThat(capturedQuery)
                .contains("keyword=error+code%3D500", "service=agent-web", "limit=20");
        assertThat(capturedAuthorization).isEqualTo("Bearer test-secret");
        assertThat(result).doesNotContain("test-secret", endpoint());
    }

    @Test
    void rejectsNonSuccessWithoutLeakingErrorBodyOrCredentials() throws Exception {
        start(exchange -> respond(exchange, 401, "application/json",
                "{\"error\":\"token test-secret rejected\"}"));
        HttpLogQueryClient client = new HttpLogQueryClient(
                endpoint(), Map.of("Authorization", "Bearer test-secret"));

        assertThatThrownBy(() -> client.query(request()))
                .isInstanceOfSatisfying(BackendQueryException.class, failure -> {
                    assertThat(failure.failure().code())
                            .isEqualTo(BackendErrorCode.AUTHENTICATION_FAILED);
                    assertThat(failure.failure().retryable()).isFalse();
                    assertThat(failure.getMessage()).doesNotContain(
                            "test-secret", endpoint(), "token");
                });
    }

    @Test
    void mapsRateLimitAndTemporaryServerFailuresAsRetryable() throws Exception {
        start(exchange -> respond(exchange, 429, "text/plain", "retry later"));
        HttpLogQueryClient client = new HttpLogQueryClient(endpoint(), Map.of());

        assertThatThrownBy(() -> client.query(request()))
                .isInstanceOfSatisfying(BackendQueryException.class, failure -> {
                    assertThat(failure.failure().code()).isEqualTo(BackendErrorCode.RATE_LIMITED);
                    assertThat(failure.failure().retryable()).isTrue();
                });
    }

    @Test
    void rejectsUnsupportedContentTypeCharsetAndOversizedBody() throws Exception {
        start(exchange -> respond(exchange, 200, "text/html", "<html>error</html>"));
        assertFailureCode(new HttpLogQueryClient(endpoint(), Map.of()),
                BackendErrorCode.PROTOCOL_ERROR);
        tearDown();

        start(exchange -> respond(exchange, 200, "text/plain; charset=ISO-8859-1", "legacy"));
        assertFailureCode(new HttpLogQueryClient(endpoint(), Map.of()),
                BackendErrorCode.PROTOCOL_ERROR);
        tearDown();

        start(exchange -> respond(exchange, 200, "text/plain", "x".repeat(65)));
        HttpLogQueryClient.Options bounded = new HttpLogQueryClient.Options(
                Duration.ofSeconds(2), 64, true);
        assertFailureCode(new HttpLogQueryClient(endpoint(), Map.of(), bounded),
                BackendErrorCode.RESPONSE_TOO_LARGE);
    }

    @Test
    void supportsExplicitLegacyTextModeAndCanDisableIt() throws Exception {
        start(exchange -> respond(exchange, 200, "text/plain; charset=utf-8", "legacy log line"));

        assertThat(new HttpLogQueryClient(endpoint(), Map.of()).query(request()))
                .isEqualTo("legacy log line");
        HttpLogQueryClient.Options typedOnly = new HttpLogQueryClient.Options(
                Duration.ofSeconds(2), 1024, false);
        assertFailureCode(new HttpLogQueryClient(endpoint(), Map.of(), typedOnly),
                BackendErrorCode.PROTOCOL_ERROR);
    }

    @Test
    void rejectsMalformedTypedResponseInsteadOfTreatingItAsLogs() throws Exception {
        start(exchange -> respond(exchange, 200, "application/json",
                "{\"entries\":[],\"matched\":-1,\"truncated\":false}"));

        assertFailureCode(new HttpLogQueryClient(endpoint(), Map.of()),
                BackendErrorCode.PROTOCOL_ERROR);
    }

    @Test
    void enforcesConfiguredRequestDeadline() throws Exception {
        start(exchange -> {
            try {
                Thread.sleep(250L);
                respond(exchange, 200, "text/plain", "late");
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                exchange.close();
            }
        });
        HttpLogQueryClient.Options shortDeadline = new HttpLogQueryClient.Options(
                Duration.ofMillis(50), 1024, true);

        assertFailureCode(new HttpLogQueryClient(endpoint(), Map.of(), shortDeadline),
                BackendErrorCode.TIMED_OUT);
    }

    @Test
    void rejectsNonHttpEndpointUserInfoAndFragments() {
        assertThatThrownBy(() -> new HttpLogQueryClient(
                "ftp://logs.test/query", Map.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new HttpLogQueryClient(
                "https://user@logs.test/query", Map.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new HttpLogQueryClient(
                "https://logs.test/query#fragment", Map.of())).isInstanceOf(IllegalArgumentException.class);
    }

    private void assertFailureCode(HttpLogQueryClient client, BackendErrorCode expected) {
        assertThatThrownBy(() -> client.query(request()))
                .isInstanceOfSatisfying(BackendQueryException.class,
                        failure -> assertThat(failure.failure().code()).isEqualTo(expected));
    }

    private void start(ExchangeHandler handler) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/logs", exchange -> {
            capturedQuery = exchange.getRequestURI().getRawQuery();
            capturedAuthorization = exchange.getRequestHeaders().getFirst("Authorization");
            handler.handle(exchange);
        });
        server.start();
    }

    private void respond(HttpExchange exchange, int status, String contentType, String body)
            throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private String endpoint() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/logs";
    }

    private LogQueryRequest request() {
        return new LogQueryRequest(
                "trace-1", "error code=500", "agent-web",
                "2026-07-30T00:00:00Z", "2026-07-30T02:00:00Z", "ERROR", 20);
    }

    @FunctionalInterface
    private interface ExchangeHandler {
        void handle(HttpExchange exchange) throws IOException;
    }
}
