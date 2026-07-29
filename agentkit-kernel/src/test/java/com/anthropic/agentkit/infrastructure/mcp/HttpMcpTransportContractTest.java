package com.anthropic.agentkit.infrastructure.mcp;

import com.anthropic.agentkit.domain.agent.AgentBudget;
import com.anthropic.agentkit.domain.agent.RunId;
import com.anthropic.agentkit.domain.agent.WorkspaceId;
import com.anthropic.agentkit.domain.conversation.CancellationToken;
import com.anthropic.agentkit.domain.tool.ExecutionContext;
import com.anthropic.agentkit.domain.tool.Tool;
import com.anthropic.agentkit.domain.tool.ToolArguments;
import com.anthropic.agentkit.domain.tool.ToolResult;
import com.anthropic.agentkit.domain.tool.ToolResultStatus;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class HttpMcpTransportContractTest {

    @Test
    void sendsScopedAuthenticationAndMapsHttpToolResult() throws Exception {
        try (FakeHttpServer server = new FakeHttpServer()) {
            McpServerConfig config = httpConfig(server.uri(), Duration.ofSeconds(2))
                    .withSecretHeader("Authorization", "MCP_TOKEN");
            ExecutionContext context = context(new CancellationToken(), "Bearer scoped-token");

            try (McpServerManager manager = new McpServerManager(List.of(config))) {
                Tool echo = tool(manager, context, "http.echo");
                ToolResult result = echo.execute(
                        ToolArguments.of(Map.of("value", "hello")), context);

                assertThat(result.status()).isEqualTo(ToolResultStatus.SUCCESS);
                assertThat(result.content()).isEqualTo("echo:hello");
                assertThat(server.authorization).hasValue("Bearer scoped-token");
            }
        }
    }

    @Test
    void redactsResolvedAuthenticationIfRemoteServerEchoesIt() throws Exception {
        try (FakeHttpServer server = new FakeHttpServer()) {
            server.echoAuthorization.set(true);
            McpServerConfig config = httpConfig(server.uri(), Duration.ofSeconds(2))
                    .withSecretHeader("Authorization", "MCP_TOKEN");
            ExecutionContext context = context(new CancellationToken(), "Bearer scoped-token");

            try (McpServerManager manager = new McpServerManager(List.of(config))) {
                ToolResult result = tool(manager, context, "http.echo")
                        .execute(ToolArguments.empty(), context);

                assertThat(result.content()).isEqualTo("echo:***");
                assertThat(result.content()).doesNotContain("scoped-token");
            }
        }
    }

    @Test
    void httpTimeoutAndCancellationHaveExplicitStatuses() throws Exception {
        try (FakeHttpServer server = new FakeHttpServer()) {
            server.slowCalls.set(true);
            McpServerConfig config = httpConfig(server.uri(), Duration.ofMillis(60));
            CancellationToken cancellation = new CancellationToken();
            ExecutionContext context = context(cancellation, null);

            try (McpServerManager manager = new McpServerManager(List.of(config))) {
                Tool slow = tool(manager, context, "http.slow");
                assertThat(slow.execute(ToolArguments.empty(), context).status())
                        .isEqualTo(ToolResultStatus.TIMEOUT);

                CompletableFuture<ToolResult> cancelled = CompletableFuture.supplyAsync(
                        () -> slow.execute(ToolArguments.empty(), context));
                assertThat(server.awaitCallCount(2)).isTrue();
                cancellation.cancel();
                assertThat(cancelled.get(1, TimeUnit.SECONDS).status())
                        .isEqualTo(ToolResultStatus.CANCELLED);
            }
        }
    }

    @Test
    void connectionFailureSettlesOnceAndReconnectsOnlyForNextInvocation() throws Exception {
        try (FakeHttpServer server = new FakeHttpServer()) {
            server.failNextCall.set(true);
            ExecutionContext context = context(new CancellationToken(), null);
            McpServerConfig config = httpConfig(server.uri(), Duration.ofSeconds(2));

            try (McpServerManager manager = new McpServerManager(List.of(config))) {
                Tool echo = tool(manager, context, "http.echo");
                ToolResult first = echo.execute(ToolArguments.empty(), context);
                ToolResult second = echo.execute(ToolArguments.empty(), context);

                assertThat(first.status()).isEqualTo(ToolResultStatus.ERROR);
                assertThat(second.status()).isEqualTo(ToolResultStatus.SUCCESS);
                assertThat(server.callCount).hasValue(2);
                assertThat(server.initializeCount).hasValueGreaterThanOrEqualTo(2);
            }
        }
    }

    private static McpServerConfig httpConfig(URI uri, Duration callTimeout) {
        return McpServerConfig.http("http", uri)
                .withInitializationTimeout(Duration.ofSeconds(2))
                .withCallTimeout(callTimeout);
    }

    private static Tool tool(
            McpServerManager manager, ExecutionContext context, String name) {
        return manager.snapshot(context).tools().stream()
                .filter(candidate -> candidate.name().equals(name))
                .findFirst().orElseThrow();
    }

    private static ExecutionContext context(
            CancellationToken cancellation, String secret) {
        Path root = Path.of(".").toAbsolutePath();
        return ExecutionContext.of(
                RunId.fresh(), WorkspaceId.fromPath(root), root, cancellation,
                AgentBudget.unlimited(), (scope, name) -> secret == null
                        ? java.util.Optional.empty() : java.util.Optional.of(secret));
    }

    private static final class FakeHttpServer implements AutoCloseable {
        private static final ObjectMapper JSON = new ObjectMapper();
        private final HttpServer server;
        private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        private final AtomicReference<String> authorization = new AtomicReference<>();
        private final AtomicInteger initializeCount = new AtomicInteger();
        private final AtomicInteger callCount = new AtomicInteger();
        private final AtomicBoolean slowCalls = new AtomicBoolean();
        private final AtomicBoolean failNextCall = new AtomicBoolean();
        private final AtomicBoolean echoAuthorization = new AtomicBoolean();

        private FakeHttpServer() throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/mcp", this::handle);
            server.setExecutor(executor);
            server.start();
        }

        private URI uri() {
            return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/mcp");
        }

        private boolean awaitCallCount(int expected) throws InterruptedException {
            for (int attempt = 0; attempt < 100 && callCount.get() < expected; attempt++) {
                Thread.sleep(10);
            }
            return callCount.get() >= expected;
        }

        private void handle(HttpExchange exchange) throws IOException {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            JsonNode request = JSON.readTree(exchange.getRequestBody());
            if (!request.hasNonNull("id")) {
                respond(exchange, 202, null);
                return;
            }
            String method = request.path("method").asText();
            if ("tools/call".equals(method) && failNextCall.compareAndSet(true, false)) {
                callCount.incrementAndGet();
                exchange.close();
                return;
            }
            respond(exchange, 200, response(request, method).toString());
        }

        private ObjectNode response(JsonNode request, String method) throws IOException {
            ObjectNode response = JSON.createObjectNode();
            response.put("jsonrpc", "2.0");
            response.set("id", request.get("id"));
            switch (method) {
                case "initialize" -> {
                    initializeCount.incrementAndGet();
                    response.set("result", initializeResult());
                }
                case "tools/list" -> response.set("result", toolsResult());
                case "tools/call" -> response.set("result", callResult(request));
                default -> response.set("result", JSON.createObjectNode());
            }
            return response;
        }

        private ObjectNode callResult(JsonNode request) throws IOException {
            callCount.incrementAndGet();
            if (slowCalls.get()) {
                try {
                    Thread.sleep(300);
                } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                }
            }
            String value = request.path("params").path("arguments")
                    .path("value").asText("");
            if (echoAuthorization.get()) {
                value = authorization.get();
            }
            ObjectNode result = JSON.createObjectNode();
            result.putArray("content").addObject()
                    .put("type", "text").put("text", "echo:" + value);
            result.put("isError", false);
            return result;
        }

        private static ObjectNode initializeResult() {
            ObjectNode result = JSON.createObjectNode();
            result.put("protocolVersion", "2025-06-18");
            result.putObject("capabilities").putObject("tools").put("listChanged", true);
            result.putObject("serverInfo").put("name", "fake-http").put("version", "1");
            return result;
        }

        private static ObjectNode toolsResult() {
            ObjectNode result = JSON.createObjectNode();
            addTool(result, "echo");
            addTool(result, "slow");
            return result;
        }

        private static void addTool(ObjectNode result, String name) {
            ObjectNode tool = result.withArray("tools").addObject();
            tool.put("name", name).put("description", "HTTP " + name);
            tool.putObject("inputSchema").put("type", "object")
                    .putObject("properties").putObject("value").put("type", "string");
            tool.putObject("annotations").put("readOnlyHint", true)
                    .put("destructiveHint", false);
        }

        private static void respond(
                HttpExchange exchange, int status, String body) throws IOException {
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.getResponseHeaders().add("Mcp-Session-Id", "fake-session");
            byte[] bytes = body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
            if (bytes.length > 0) {
                exchange.getResponseBody().write(bytes);
            }
            exchange.close();
        }

        @Override
        public void close() {
            server.stop(0);
            executor.shutdownNow();
        }
    }
}
