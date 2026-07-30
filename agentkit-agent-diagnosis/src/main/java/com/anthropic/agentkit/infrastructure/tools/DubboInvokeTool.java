package com.anthropic.agentkit.infrastructure.tools;

import com.anthropic.agentkit.domain.tool.ExecutionContext;
import com.anthropic.agentkit.domain.tool.Tool;
import com.anthropic.agentkit.domain.tool.ToolArguments;
import com.anthropic.agentkit.domain.tool.ToolResult;
import com.anthropic.agentkit.infrastructure.tools.support.DubboTelnetClient;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

    private static final Logger log = LoggerFactory.getLogger(DubboInvokeTool.class);

    private static final int DEFAULT_TIMEOUT_MS = 10_000;
    private static final int MAX_TIMEOUT_MS = 30_000;
    private static final List<String> READ_PREFIXES = List.of(
            "get", "query", "list", "find", "count", "exist", "load",
            "select", "fetch", "search", "page", "is", "has");

    private final DubboTelnetClient client;
    private final Set<String> allowedAddresses;
    private final Set<String> allowedMethods;
    private final boolean strictAllowlists;

    public DubboInvokeTool(DubboTelnetClient client) {
        this(client, Set.of(), Set.of(), false);
    }

    public DubboInvokeTool(DubboTelnetClient client, Set<String> allowedMethods) {
        this(client, Set.of(), allowedMethods, false);
    }

    public DubboInvokeTool(DubboTelnetClient client, Set<String> allowedAddresses,
                           Set<String> allowedMethods) {
        this(client, allowedAddresses, allowedMethods, true);
    }

    private DubboInvokeTool(DubboTelnetClient client, Set<String> allowedAddresses,
                            Set<String> allowedMethods, boolean strictAllowlists) {
        this.client = Objects.requireNonNull(client, "client");
        this.allowedAddresses = normalizeAddresses(allowedAddresses);
        this.allowedMethods = normalizeMethods(allowedMethods);
        this.strictAllowlists = strictAllowlists;
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
        long startNs = System.nanoTime();
        String address = args.getString("address", "").trim();
        String invocation = args.getString("invocation", "").trim();
        if (address.isEmpty()) {
            log.warn("dubbo invoke blocked: reason=missing_address");
            return ToolResult.error("DubboInvoke requires 'address' (host:port)");
        }
        if (invocation.isEmpty()) {
            log.warn("dubbo invoke blocked: reason=missing_invocation");
            return ToolResult.error("DubboInvoke requires 'invocation' (Service.method(args))");
        }
        if (!validAddress(address) || strictAllowlists && !allowedAddresses.contains(address)) {
            log.warn("dubbo invoke blocked: reason=address_not_allowlisted");
            return ToolResult.error("DubboInvoke address is not allowlisted");
        }
        String method = methodName(invocation);
        log.debug("dubbo invoke args: method={}", method);
        if (!isReadMethod(method)) {
            log.warn("dubbo invoke blocked: reason=not_read_method, method={}", method);
            return ToolResult.error(
                    "DubboInvoke permits only read-shaped methods (get/query/list/...)");
        }
        if (!isAllowlisted(invocation, method)) {
            log.warn("dubbo invoke blocked: reason=not_allowlisted, method={}", method);
            return ToolResult.error("DubboInvoke method is not allowlisted: " + method);
        }
        int timeoutMs = args.getInt("timeoutMs", DEFAULT_TIMEOUT_MS);
        if (timeoutMs <= 0 || timeoutMs > MAX_TIMEOUT_MS) {
            return ToolResult.error("DubboInvoke timeoutMs is outside the allowed range");
        }
        Duration timeout = Duration.ofMillis(timeoutMs);
        try {
            String output = client.invoke(address, invocation, timeout);
            log.info("dubbo invoke completed: method={}, chars={}, durationMs={}",
                    method, output.length(), elapsedMs(startNs));
            return ToolResult.ok(output);
        } catch (IOException ex) {
            log.error("dubbo invoke failed: method={}, failureType={}",
                    method, ex.getClass().getSimpleName());
            return ToolResult.error("DubboInvoke failed: backend request could not be completed");
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
        return !strictAllowlists && allowedMethods.isEmpty()
                || allowedMethods.contains(methodIdentifier(invocation))
                || allowedMethods.contains(method);
    }

    private static Set<String> normalizeAddresses(Set<String> addresses) {
        Set<String> result = normalizeMethods(addresses);
        if (result.stream().anyMatch(address -> !validAddress(address))) {
            throw new IllegalArgumentException("Dubbo addresses must use exact host:port form");
        }
        return result;
    }

    private static boolean validAddress(String address) {
        return address.matches("[A-Za-z0-9.-]{1,253}:[1-9][0-9]{0,4}")
                && port(address) <= 65535;
    }

    private static int port(String address) {
        try {
            return Integer.parseInt(address.substring(address.lastIndexOf(':') + 1));
        } catch (RuntimeException failure) {
            return Integer.MAX_VALUE;
        }
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

    private static long elapsedMs(long startNs) {
        return (System.nanoTime() - startNs) / 1_000_000L;
    }
}
