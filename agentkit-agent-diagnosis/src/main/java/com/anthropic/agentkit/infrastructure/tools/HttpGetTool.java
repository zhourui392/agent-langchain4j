package com.anthropic.agentkit.infrastructure.tools;

import com.anthropic.agentkit.domain.tool.ExecutionContext;
import com.anthropic.agentkit.domain.tool.Tool;
import com.anthropic.agentkit.domain.tool.ToolArguments;
import com.anthropic.agentkit.domain.tool.ToolResult;
import com.anthropic.agentkit.infrastructure.tools.support.LogSanitizer;
import com.anthropic.agentkit.infrastructure.tools.support.HttpReader;
import com.anthropic.agentkit.infrastructure.tools.support.HttpReader.HttpResponseView;
import com.anthropic.agentkit.infrastructure.tools.support.HostAddressResolver;

import java.io.IOException;
import java.net.URI;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Read-only HTTP GET for verifying endpoints during diagnosis.
 *
 * @author zhourui(V33215020)
 * @since 2026-06-08
 */
public final class HttpGetTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(HttpGetTool.class);

    private static final int DEFAULT_TIMEOUT_MS = 15_000;
    private static final int MAX_TIMEOUT_MS = 30_000;
    private static final Set<String> SAFE_HEADERS = Set.of(
            "accept", "user-agent", "x-request-id", "x-trace-id");

    private final HttpReader httpReader;
    private final Set<String> allowedHosts;
    private final HostAddressResolver addressResolver;

    public HttpGetTool(HttpReader httpReader) {
        this(httpReader, Set.of(), HostAddressResolver.system());
    }

    public HttpGetTool(HttpReader httpReader, Set<String> allowedHosts) {
        this(httpReader, allowedHosts, HostAddressResolver.system());
    }

    public HttpGetTool(HttpReader httpReader, Set<String> allowedHosts,
                       HostAddressResolver addressResolver) {
        this.httpReader = Objects.requireNonNull(httpReader, "httpReader");
        this.allowedHosts = normalizeHosts(allowedHosts);
        this.addressResolver = Objects.requireNonNull(addressResolver, "addressResolver");
    }

    @Override
    public String name() {
        return "HttpGet";
    }

    @Override
    public String description() {
        return "Issue a read-only HTTP GET to verify an endpoint; returns status and body.";
    }

    @Override
    public String inputSchema() {
        return "{\"type\":\"object\",\"properties\":{"
                + "\"url\":{\"type\":\"string\"},"
                + "\"headers\":{\"type\":\"object\"},"
                + "\"timeoutMs\":{\"type\":\"integer\"}},"
                + "\"required\":[\"url\"]}";
    }

    @Override
    public boolean isReadOnly() {
        return true;
    }

    @Override
    public ToolResult execute(ToolArguments args, ExecutionContext ctx) {
        long startNs = System.nanoTime();
        String url = args.getString("url", "").trim();
        URI target = target(url);
        if (target == null) {
            log.warn("http get blocked: reason=invalid_url");
            return ToolResult.error("HttpGet requires a safe absolute HTTP(S) URL");
        }
        String host = target.getHost().toLowerCase();
        if (!allowedHosts.contains(host)) {
            log.warn("http get blocked: reason=host_not_allowlisted, host={}", host);
            return ToolResult.error("HttpGet host is not allowlisted: " + host);
        }
        Map<String, String> requestHeaders = headers(args);
        if (requestHeaders == null) {
            return ToolResult.error("HttpGet contains a forbidden request header");
        }
        int timeoutMs = args.getInt("timeoutMs", DEFAULT_TIMEOUT_MS);
        if (timeoutMs <= 0 || timeoutMs > MAX_TIMEOUT_MS) {
            return ToolResult.error("HttpGet timeoutMs is outside the allowed range");
        }
        InetAddress approvedAddress = approvedAddress(host);
        if (approvedAddress == null) {
            return ToolResult.error("HttpGet target resolves to a forbidden network address");
        }
        Duration timeout = Duration.ofMillis(timeoutMs);
        log.debug("http get args: host={}, headers={}, timeoutMs={}",
                host, requestHeaders.keySet(), timeout.toMillis());
        try {
            HttpResponseView response = httpReader.getPinned(
                    target, approvedAddress, requestHeaders, timeout);
            log.info("http get completed: host={}, status={}, responseBytes={}, durationMs={}",
                    host, response.statusCode(),
                    response.body().getBytes(StandardCharsets.UTF_8).length,
                    elapsedMs(startNs));
            return ToolResult.ok("HTTP " + response.statusCode() + "\n" + response.body());
        } catch (IOException ex) {
            log.error("http get failed: host={}, failureType={}",
                    host, ex.getClass().getSimpleName());
            return ToolResult.error("HttpGet failed: backend request could not be completed");
        }
    }

    private static URI target(String url) {
        try {
            URI uri = URI.create(url);
            boolean scheme = "http".equalsIgnoreCase(uri.getScheme())
                    || "https".equalsIgnoreCase(uri.getScheme());
            boolean port = uri.getPort() == -1 || uri.getPort() > 0 && uri.getPort() <= 65535;
            return scheme && uri.isAbsolute() && uri.getHost() != null
                    && uri.getUserInfo() == null && uri.getFragment() == null && port ? uri : null;
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private InetAddress approvedAddress(String host) {
        try {
            Set<InetAddress> addresses = addressResolver.resolve(host);
            if (addresses.isEmpty() || addresses.stream().anyMatch(HttpGetTool::forbidden)) {
                return null;
            }
            return addresses.stream().sorted(java.util.Comparator.comparing(
                    InetAddress::getHostAddress)).findFirst().orElse(null);
        } catch (IOException | RuntimeException failure) {
            return null;
        }
    }

    private static boolean forbidden(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress()
                || address.isLinkLocalAddress() || address.isSiteLocalAddress()
                || address.isMulticastAddress()) {
            return true;
        }
        byte[] bytes = address.getAddress();
        return bytes.length == 16 && (bytes[0] & 0xfe) == 0xfc
                || bytes.length == 4 && (bytes[0] & 0xff) == 100
                && (bytes[1] & 0xc0) == 64;
    }

    private static Set<String> normalizeHosts(Set<String> hosts) {
        if (hosts == null || hosts.isEmpty()) {
            return Set.of();
        }
        return hosts.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(host -> !host.isBlank())
                .map(String::toLowerCase)
                .collect(Collectors.toUnmodifiableSet());
    }

    private static Map<String, String> headers(ToolArguments args) {
        Object raw = args.values().get("headers");
        if (!(raw instanceof Map<?, ?> rawMap)) {
            return Map.of();
        }
        Map<String, String> headers = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : rawMap.entrySet()) {
            String name = String.valueOf(entry.getKey()).trim();
            String value = String.valueOf(entry.getValue());
            if (!SAFE_HEADERS.contains(name.toLowerCase()) || value.contains("\r")
                    || value.contains("\n") || value.length() > 1024) {
                return null;
            }
            headers.put(name, value);
        }
        return Map.copyOf(headers);
    }

    private static long elapsedMs(long startNs) {
        return (System.nanoTime() - startNs) / 1_000_000L;
    }
}
