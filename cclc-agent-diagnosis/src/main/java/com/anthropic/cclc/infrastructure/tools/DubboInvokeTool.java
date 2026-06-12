package com.anthropic.cclc.infrastructure.tools;

import com.anthropic.cclc.domain.tool.ExecutionContext;
import com.anthropic.cclc.domain.tool.Tool;
import com.anthropic.cclc.domain.tool.ToolArguments;
import com.anthropic.cclc.domain.tool.ToolResult;
import com.anthropic.cclc.infrastructure.tools.support.DubboTelnetClient;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Read-only Dubbo telnet invoke. Because {@code invoke} can reach any method, a
 * method-name guard permits only read-shaped methods (get/query/list/...).
 *
 * <p>The guard is a heuristic over method-name prefixes — defence in depth for a
 * read-only engine, not a hard guarantee that the target method is side-effect
 * free.
 *
 * @author zhourui(V33215020)
 * @since 2026-06-08
 */
public final class DubboInvokeTool implements Tool {

    private static final int DEFAULT_TIMEOUT_MS = 10_000;
    private static final List<String> READ_PREFIXES = List.of(
            "get", "query", "list", "find", "count", "exist", "load",
            "select", "fetch", "search", "page", "is", "has");

    private final DubboTelnetClient client;
    private final Set<String> allowedMethods;

    public DubboInvokeTool(DubboTelnetClient client) {
        this(client, Set.of());
    }

    public DubboInvokeTool(DubboTelnetClient client, Set<String> allowedMethods) {
        this.client = Objects.requireNonNull(client, "client");
        this.allowedMethods = normalizeMethods(allowedMethods);
    }

    @Override
    public String name() {
        return "DubboInvoke";
    }

    @Override
    public String description() {
        return "Read-only Dubbo telnet invoke against a provider address; "
                + "only read-shaped methods (get/query/list/find/count/...) are permitted.";
    }

    @Override
    public String inputSchema() {
        return "{\"type\":\"object\",\"properties\":{"
                + "\"address\":{\"type\":\"string\"},"
                + "\"invocation\":{\"type\":\"string\"},"
                + "\"timeoutMs\":{\"type\":\"integer\"}},"
                + "\"required\":[\"address\",\"invocation\"]}";
    }

    @Override
    public boolean isReadOnly() {
        return true;
    }

    @Override
    public ToolResult execute(ToolArguments args, ExecutionContext ctx) {
        String address = args.getString("address", "").trim();
        String invocation = args.getString("invocation", "").trim();
        if (address.isEmpty()) {
            return ToolResult.error("DubboInvoke requires 'address' (host:port)");
        }
        if (invocation.isEmpty()) {
            return ToolResult.error("DubboInvoke requires 'invocation' (Service.method(args))");
        }
        String method = methodName(invocation);
        if (!isReadMethod(method)) {
            return ToolResult.error(
                    "DubboInvoke permits only read-shaped methods (get/query/list/...): " + invocation);
        }
        if (!isAllowlisted(invocation, method)) {
            return ToolResult.error("DubboInvoke method is not allowlisted: " + method);
        }
        Duration timeout = Duration.ofMillis(args.getInt("timeoutMs", DEFAULT_TIMEOUT_MS));
        try {
            return ToolResult.ok(client.invoke(address, invocation, timeout));
        } catch (IOException ex) {
            return ToolResult.error("DubboInvoke failed: " + ex.getMessage());
        }
    }

    private static String methodName(String invocation) {
        int paren = invocation.indexOf('(');
        String beforeParen = paren >= 0 ? invocation.substring(0, paren) : invocation;
        int lastDot = beforeParen.lastIndexOf('.');
        return (lastDot >= 0 ? beforeParen.substring(lastDot + 1) : beforeParen).trim();
    }

    private static String methodIdentifier(String invocation) {
        int paren = invocation.indexOf('(');
        return (paren >= 0 ? invocation.substring(0, paren) : invocation).trim();
    }

    private static boolean isReadMethod(String method) {
        String lower = method.toLowerCase();
        return READ_PREFIXES.stream().anyMatch(lower::startsWith);
    }

    private boolean isAllowlisted(String invocation, String method) {
        return allowedMethods.isEmpty()
                || allowedMethods.contains(methodIdentifier(invocation))
                || allowedMethods.contains(method);
    }

    private static Set<String> normalizeMethods(Set<String> methods) {
        if (methods == null || methods.isEmpty()) {
            return Set.of();
        }
        return methods.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(method -> !method.isBlank())
                .collect(Collectors.toUnmodifiableSet());
    }
}
