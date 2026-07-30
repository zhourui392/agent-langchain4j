package com.anthropic.agentkit.infrastructure.tools.support;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HttpClientAdaptersTest {

    private HttpServer server;
    private String lastPath;
    private String lastMethod;
    private String lastHeader;
    private String lastBody;
    private int redirectTargetHits;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void jdkHttpReaderSendsGetWithHeaders() throws Exception {
        startServer("body");
        JdkHttpReader reader = new JdkHttpReader();

        HttpReader.HttpResponseView response = reader.get(
                baseUrl() + "/health",
                Map.of("X-Test", "yes"),
                Duration.ofSeconds(2));

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo("body");
        assertThat(lastMethod).isEqualTo("GET");
        assertThat(lastHeader).isEqualTo("yes");
    }

    @Test
    void jdkHttpReaderDoesNotFollowRedirectsOutsideTheValidatedRequest() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/start", exchange -> {
            exchange.getResponseHeaders().add("Location", baseUrl() + "/target");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.createContext("/target", exchange -> {
            redirectTargetHits++;
            respond(exchange, "redirected");
        });
        server.start();
        JdkHttpReader reader = new JdkHttpReader();

        HttpReader.HttpResponseView response = reader.get(
                baseUrl() + "/start", Map.of(), Duration.ofSeconds(2));

        assertThat(response.statusCode()).isEqualTo(302);
        assertThat(redirectTargetHits).isZero();
    }

    @Test
    void httpEsReadClientUsesExpectedEndpoints() throws Exception {
        startServer("{\"count\":7}");
        HttpEsReadClient client = new HttpEsReadClient(baseUrl() + "/");

        assertThat(client.count("order index", "")).isEqualTo(7);
        assertThat(lastPath).isEqualTo("/order+index/_count");
        assertThat(lastBody).isEqualTo("{}");

        client.search("order", "{\"query\":{}}", 3);
        assertThat(lastPath).isEqualTo("/order/_search?size=3");
        assertThat(lastBody).isEqualTo("{\"query\":{}}");

        client.get("order", "id 1");
        assertThat(lastPath).isEqualTo("/order/_doc/id+1");

        client.mapping("order");
        assertThat(lastPath).isEqualTo("/order/_mapping");
    }

    @Test
    void httpEsReadClientRejectsNonSuccessAndUnsafeBaseUrl() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            byte[] body = "credential=sentinel-secret".getBytes();
            exchange.sendResponseHeaders(401, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        HttpEsReadClient client = new HttpEsReadClient(baseUrl());

        assertThatThrownBy(() -> client.search("logs", "{}", 10))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("AUTHENTICATION_FAILED")
                .hasMessageNotContaining("sentinel-secret")
                .hasMessageNotContaining(baseUrl());
        assertThatThrownBy(() -> new HttpEsReadClient("ftp://es.test"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private void startServer(String responseBody) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> respond(exchange, responseBody));
        server.start();
    }

    private void respond(HttpExchange exchange, String responseBody) throws IOException {
        lastPath = exchange.getRequestURI().toString();
        lastMethod = exchange.getRequestMethod();
        lastHeader = exchange.getRequestHeaders().getFirst("X-Test");
        lastBody = new String(exchange.getRequestBody().readAllBytes());
        byte[] bytes = responseBody.getBytes();
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }
}
