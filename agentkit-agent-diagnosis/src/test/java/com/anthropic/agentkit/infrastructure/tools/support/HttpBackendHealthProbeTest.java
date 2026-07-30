package com.anthropic.agentkit.infrastructure.tools.support;

import com.anthropic.agentkit.domain.diagnosis.ReadinessStatus;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * @author alex
 */
class HttpBackendHealthProbeTest {

    private HttpServer server;
    private String authorization;
    private String method;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void executesOnlyFixedHostHealthRequestAndReturnsSecretFreeReadyStatus() throws Exception {
        start(exchange -> respond(exchange, 204));

        BackendHealth health = probe().probe();

        assertThat(method).isEqualTo("GET");
        assertThat(authorization).isEqualTo("Bearer health-secret");
        assertThat(health.status()).isEqualTo(ReadinessStatus.READY);
        assertThat(health.reasonCode()).isEqualTo("BACKEND_READY");
        assertThat(health.toString()).doesNotContain("health-secret", endpoint());
    }

    @Test
    void mapsAuthenticationAndTemporaryFailuresWithoutReadingErrorBody() throws Exception {
        start(exchange -> {
            exchange.getResponseHeaders().set("X-Secret", "health-secret");
            respond(exchange, 401);
        });
        BackendHealth authentication = probe().probe();
        assertThat(authentication.status()).isEqualTo(ReadinessStatus.UNAVAILABLE);
        assertThat(authentication.reasonCode()).isEqualTo("BACKEND_AUTHENTICATION_FAILED");
        assertThat(authentication.toString()).doesNotContain("health-secret", endpoint());
        tearDown();

        start(exchange -> respond(exchange, 503));
        BackendHealth temporary = probe().probe();
        assertThat(temporary.status()).isEqualTo(ReadinessStatus.DEGRADED);
        assertThat(temporary.reasonCode()).isEqualTo("BACKEND_UNAVAILABLE");
    }

    private HttpBackendHealthProbe probe() {
        return new HttpBackendHealthProbe(endpoint(),
                Map.of("Authorization", "Bearer health-secret"),
                Duration.ofSeconds(2),
                HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build(),
                Clock.fixed(Instant.parse("2026-07-30T04:00:00Z"), ZoneOffset.UTC));
    }

    private void start(ExchangeHandler handler) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/health", exchange -> {
            authorization = exchange.getRequestHeaders().getFirst("Authorization");
            method = exchange.getRequestMethod();
            handler.handle(exchange);
        });
        server.start();
    }

    private void respond(HttpExchange exchange, int status) throws IOException {
        exchange.sendResponseHeaders(status, -1L);
        exchange.close();
    }

    private String endpoint() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/health";
    }

    @FunctionalInterface
    private interface ExchangeHandler {
        void handle(HttpExchange exchange) throws IOException;
    }
}
