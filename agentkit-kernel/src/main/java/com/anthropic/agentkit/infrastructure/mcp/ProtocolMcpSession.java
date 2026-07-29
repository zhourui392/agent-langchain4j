package com.anthropic.agentkit.infrastructure.mcp;

import com.anthropic.agentkit.domain.conversation.CancellationToken;
import com.anthropic.agentkit.domain.tool.ExecutionContext;
import com.anthropic.agentkit.domain.tool.ToolArguments;
import com.fasterxml.jackson.databind.JsonNode;
import dev.langchain4j.mcp.client.McpRoot;
import dev.langchain4j.mcp.client.logging.McpLogMessage;
import dev.langchain4j.mcp.client.protocol.McpCallToolRequest;
import dev.langchain4j.mcp.client.protocol.McpCancellationNotification;
import dev.langchain4j.mcp.client.protocol.McpClientMessage;
import dev.langchain4j.mcp.client.protocol.McpInitializeParams;
import dev.langchain4j.mcp.client.protocol.McpInitializeRequest;
import dev.langchain4j.mcp.client.protocol.McpListToolsRequest;
import dev.langchain4j.mcp.client.transport.McpOperationHandler;
import dev.langchain4j.mcp.client.transport.McpTransport;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Minimal raw MCP client over LangChain4j transports, preserving annotations. */
final class ProtocolMcpSession implements McpSession {

    private static final Logger log = LoggerFactory.getLogger(ProtocolMcpSession.class);
    private static final String PROTOCOL_VERSION = "2025-06-18";

    private final McpServerConfig config;
    private final McpTransport transport;
    private final CancellationToken sessionCancellation;
    private final McpProtocolMapper protocol;
    private final AtomicLong ids = new AtomicLong(1);
    private final ConcurrentMap<Long, CompletableFuture<JsonNode>> pending =
            new ConcurrentHashMap<>();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean transportFailed = new AtomicBoolean();

    private ProtocolMcpSession(
            McpServerConfig config, McpTransport transport,
            ExecutionContext context, List<String> secretValues) {
        this.config = Objects.requireNonNull(config, "config");
        this.transport = Objects.requireNonNull(transport, "transport");
        this.sessionCancellation = context.cancellation();
        this.protocol = new McpProtocolMapper(secretValues);
    }

    static ProtocolMcpSession open(
            McpServerConfig config, McpTransport transport,
            ExecutionContext context, List<String> secretValues) {
        ProtocolMcpSession session = new ProtocolMcpSession(
                config, transport, context, secretValues);
        try {
            session.start();
            return session;
        } catch (RuntimeException failure) {
            session.close();
            throw failure;
        }
    }

    @Override
    public List<McpToolDescriptor> discoverTools() {
        requireOpen();
        List<McpToolDescriptor> descriptors = new ArrayList<>();
        String cursor = null;
        do {
            McpListToolsRequest request = new McpListToolsRequest(ids.getAndIncrement());
            if (cursor != null) {
                request.setCursor(cursor);
            }
            JsonNode response = execute(
                    request, config.initializationTimeout(),
                    sessionCancellation, "tools/list");
            JsonNode result = protocol.result(response, "tools/list");
            descriptors.addAll(protocol.descriptors(result.path("tools")));
            cursor = protocol.nextCursor(result.get("nextCursor"));
        } while (cursor != null);
        return List.copyOf(descriptors);
    }

    @Override
    public McpCallResult call(
            String toolName, ToolArguments arguments, ExecutionContext context) {
        requireOpen();
        if (context.cancellation().isCancelled()) {
            throw new McpCancelledException("MCP call cancelled");
        }
        long id = ids.getAndIncrement();
        McpCallToolRequest request = new McpCallToolRequest(
                id, toolName, protocol.arguments(arguments));
        Duration timeout = minimum(config.callTimeout(), context.limits().toolWait());
        JsonNode response = execute(request, timeout, context.cancellation(), "tools/call");
        return protocol.callResult(response);
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        pending.values().forEach(future ->
                future.completeExceptionally(new LocalCancellation("session closed")));
        pending.clear();
        try {
            transport.close();
        } catch (Exception failure) {
            log.warn("failed to close MCP transport: server={}, errorType={}",
                    config.id(), failure.getClass().getSimpleName());
        }
    }

    private void start() {
        McpOperationHandler handler = new McpOperationHandler(
                pending, List::<McpRoot>of, transport,
                this::observeServerLog, () -> { });
        transport.onFailure(() -> transportFailed.set(true));
        transport.start(handler);
        long id = ids.getAndIncrement();
        McpInitializeRequest request = new McpInitializeRequest(id);
        request.setParams(initializeParams());
        CompletableFuture<JsonNode> future = transport.initialize(request);
        JsonNode response = await(id, future, config.initializationTimeout(),
                sessionCancellation, "initialize");
        protocol.result(response, "initialize");
        log.info("MCP session ready: server={}", config.id());
    }

    private JsonNode execute(
            McpClientMessage request, Duration timeout,
            CancellationToken cancellation, String operation) {
        if (transportFailed.get()) {
            throw new McpConnectionException("MCP transport is unavailable");
        }
        CompletableFuture<JsonNode> future;
        try {
            future = transport.executeOperationWithResponse(request);
        } catch (RuntimeException failure) {
            throw new McpConnectionException("failed to submit MCP operation", failure);
        }
        return await(request.getId(), future, timeout, cancellation, operation);
    }

    private JsonNode await(
            long id, CompletableFuture<JsonNode> future, Duration timeout,
            CancellationToken cancellation, String operation) {
        try (var ignored = cancellation.onCancel(() -> cancel(id, future, "Cancelled"))) {
            return future.get(timeout.toNanos(), TimeUnit.NANOSECONDS);
        } catch (TimeoutException failure) {
            cancel(id, future, "Timeout");
            throw new McpTimeoutException("MCP " + operation + " timed out", failure);
        } catch (CancellationException failure) {
            throw new McpCancelledException("MCP " + operation + " cancelled", failure);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            cancel(id, future, "Interrupted");
            throw new McpCancelledException("MCP " + operation + " interrupted", failure);
        } catch (ExecutionException failure) {
            if (failure.getCause() instanceof LocalCancellation) {
                throw new McpCancelledException(
                        "MCP " + operation + " cancelled", failure.getCause());
            }
            throw new McpConnectionException("MCP " + operation + " failed", failure.getCause());
        } finally {
            pending.remove(id);
        }
    }

    private void cancel(long id, CompletableFuture<JsonNode> future, String reason) {
        future.completeExceptionally(new LocalCancellation(reason));
        try {
            transport.executeOperationWithoutResponse(
                    new McpCancellationNotification(id, reason));
        } catch (RuntimeException ignored) {
            // The transport may already be gone; local cancellation still wins.
        }
    }

    private void observeServerLog(McpLogMessage message) {
        log.debug("MCP server log received: server={}, level={}, logger={}",
                config.id(), message.level(), message.logger());
    }

    private void requireOpen() {
        if (closed.get()) {
            throw new McpConnectionException("MCP session is closed");
        }
    }

    private static McpInitializeParams initializeParams() {
        McpInitializeParams params = new McpInitializeParams();
        params.setProtocolVersion(PROTOCOL_VERSION);
        McpInitializeParams.ClientInfo info = new McpInitializeParams.ClientInfo();
        info.setName("agentkit-kernel");
        info.setVersion("0.2.0");
        params.setClientInfo(info);
        params.setCapabilities(new McpInitializeParams.Capabilities());
        return params;
    }

    private static Duration minimum(Duration first, Duration second) {
        return first.compareTo(second) <= 0 ? first : second;
    }

    private static final class LocalCancellation extends RuntimeException {
        private LocalCancellation(String reason) {
            super(reason);
        }
    }
}
