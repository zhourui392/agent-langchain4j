package com.anthropic.agentkit.infrastructure.tools.support;

import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Bounded HTTP/1.1 GET transport that connects to an already-approved IP address.
 * Redirects are returned as ordinary responses and never followed. HTTPS retains
 * hostname verification and SNI while the TCP connection remains pinned to the
 * approved address, closing the DNS-rebinding gap between policy and transport.
 *
 * @author zhourui(V33215020)
 * @since 2026-06-08
 */
public final class JdkHttpReader implements HttpReader {

    private static final Logger log = LoggerFactory.getLogger(JdkHttpReader.class);
    private static final int MAX_BODY_BYTES = 1024 * 1024;
    private static final int MAX_HEADER_BYTES = 64 * 1024;
    private static final int MAX_HEADER_COUNT = 100;
    private static final int MAX_LINE_BYTES = 8192;

    @Override
    public HttpResponseView get(String url, Map<String, String> headers,
                                Duration timeout) throws IOException {
        URI target = URI.create(url);
        InetAddress[] addresses = InetAddress.getAllByName(target.getHost());
        if (addresses.length == 0) {
            throw new IOException("http target did not resolve");
        }
        return getPinned(target, addresses[0], headers, timeout);
    }

    @Override
    public HttpResponseView getPinned(URI target, InetAddress address,
                                      Map<String, String> headers,
                                      Duration timeout) throws IOException {
        long startNs = System.nanoTime();
        validate(target, address, headers, timeout);
        int timeoutMs = Math.toIntExact(Math.max(1, timeout.toMillis()));
        int port = effectivePort(target);
        log.debug("jdk http get started: host={}, pinnedAddress={}, timeoutMs={}",
                target.getHost(), address.getHostAddress(), timeoutMs);
        try (Socket socket = connect(target, address, port, timeoutMs)) {
            writeRequest(socket.getOutputStream(), target, port, headers);
            HttpResponseView response = readResponse(socket.getInputStream());
            log.debug("jdk http get completed: host={}, status={}, bytes={}, durationMs={}",
                    target.getHost(), response.statusCode(),
                    response.body().getBytes(StandardCharsets.UTF_8).length,
                    elapsedMs(startNs));
            return response;
        }
    }

    private static Socket connect(URI target, InetAddress address, int port,
                                  int timeoutMs) throws IOException {
        Socket plain = new Socket();
        try {
            plain.connect(new InetSocketAddress(address, port), timeoutMs);
            plain.setSoTimeout(timeoutMs);
            if ("http".equalsIgnoreCase(target.getScheme())) {
                return plain;
            }
            SSLSocketFactory factory = (SSLSocketFactory) SSLSocketFactory.getDefault();
            SSLSocket tls = (SSLSocket) factory.createSocket(
                    plain, target.getHost(), port, true);
            SSLParameters parameters = tls.getSSLParameters();
            parameters.setEndpointIdentificationAlgorithm("HTTPS");
            if (!literalAddress(target.getHost())) {
                parameters.setServerNames(List.of(new SNIHostName(target.getHost())));
            }
            tls.setSSLParameters(parameters);
            tls.setSoTimeout(timeoutMs);
            tls.startHandshake();
            return tls;
        } catch (IOException | RuntimeException failure) {
            try {
                plain.close();
            } catch (IOException ignored) {
                // Preserve the connection/handshake failure.
            }
            throw failure;
        }
    }

    private static void writeRequest(OutputStream raw, URI target, int port,
                                     Map<String, String> headers) throws IOException {
        BufferedOutputStream output = new BufferedOutputStream(raw);
        StringBuilder request = new StringBuilder()
                .append("GET ").append(requestTarget(target)).append(" HTTP/1.1\r\n")
                .append("Host: ").append(hostHeader(target, port)).append("\r\n")
                .append("Accept-Encoding: identity\r\n")
                .append("Connection: close\r\n");
        headers.forEach((name, value) -> request.append(name).append(": ")
                .append(value).append("\r\n"));
        request.append("\r\n");
        output.write(request.toString().getBytes(StandardCharsets.US_ASCII));
        output.flush();
    }

    private static HttpResponseView readResponse(InputStream raw) throws IOException {
        BufferedInputStream input = new BufferedInputStream(raw);
        String statusLine = readLine(input, MAX_LINE_BYTES);
        int status = status(statusLine);
        Map<String, String> headers = readHeaders(input);
        byte[] body = readBody(input, status, headers);
        return new HttpResponseView(status, decode(body));
    }

    private static Map<String, String> readHeaders(InputStream input) throws IOException {
        Map<String, String> headers = new LinkedHashMap<>();
        int totalBytes = 0;
        for (int count = 0; count < MAX_HEADER_COUNT; count++) {
            String line = readLine(input, MAX_LINE_BYTES);
            totalBytes += line.getBytes(StandardCharsets.ISO_8859_1).length + 2;
            if (totalBytes > MAX_HEADER_BYTES) {
                throw new IOException("http response headers exceeded the configured limit");
            }
            if (line.isEmpty()) {
                return Map.copyOf(headers);
            }
            if (Character.isWhitespace(line.charAt(0))) {
                throw new IOException("http folded response headers are not supported");
            }
            int colon = line.indexOf(':');
            if (colon <= 0) {
                throw new IOException("http response contained an invalid header");
            }
            String name = line.substring(0, colon).trim().toLowerCase(Locale.ROOT);
            String value = line.substring(colon + 1).trim();
            headers.merge(name, value, (left, right) -> left + "," + right);
        }
        throw new IOException("http response contained too many headers");
    }

    private static byte[] readBody(InputStream input, int status,
                                   Map<String, String> headers) throws IOException {
        if (status == 204 || status == 304 || status >= 100 && status < 200) {
            return new byte[0];
        }
        String transfer = headers.getOrDefault("transfer-encoding", "");
        if (!transfer.isBlank()) {
            if (!"chunked".equalsIgnoreCase(transfer.trim())) {
                throw new IOException("unsupported http transfer encoding");
            }
            return readChunked(input);
        }
        String length = headers.get("content-length");
        if (length != null) {
            long parsed = parseLength(length);
            if (parsed > MAX_BODY_BYTES) {
                throw new IOException("http response exceeded the configured limit");
            }
            byte[] body = input.readNBytes((int) parsed);
            if (body.length != parsed) {
                throw new IOException("http response ended before content-length");
            }
            return body;
        }
        return readUntilEnd(input, MAX_BODY_BYTES);
    }

    private static byte[] readChunked(InputStream input) throws IOException {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        while (true) {
            String line = readLine(input, MAX_LINE_BYTES);
            int extension = line.indexOf(';');
            String token = (extension >= 0 ? line.substring(0, extension) : line).trim();
            int size;
            try {
                size = Integer.parseUnsignedInt(token, 16);
            } catch (NumberFormatException failure) {
                throw new IOException("http response contained an invalid chunk size", failure);
            }
            if (size == 0) {
                readTrailers(input);
                return body.toByteArray();
            }
            if (size > MAX_BODY_BYTES - body.size()) {
                throw new IOException("http response exceeded the configured limit");
            }
            byte[] chunk = input.readNBytes(size);
            if (chunk.length != size) {
                throw new IOException("http chunk ended early");
            }
            body.writeBytes(chunk);
            requireCrLf(input);
        }
    }

    private static void readTrailers(InputStream input) throws IOException {
        int bytes = 0;
        for (int count = 0; count < MAX_HEADER_COUNT; count++) {
            String line = readLine(input, MAX_LINE_BYTES);
            bytes += line.length() + 2;
            if (bytes > MAX_HEADER_BYTES) {
                throw new IOException("http trailers exceeded the configured limit");
            }
            if (line.isEmpty()) {
                return;
            }
        }
        throw new IOException("http response contained too many trailers");
    }

    private static byte[] readUntilEnd(InputStream input, int maxBytes) throws IOException {
        byte[] bytes = input.readNBytes(maxBytes + 1);
        if (bytes.length > maxBytes) {
            throw new IOException("http response exceeded the configured limit");
        }
        return bytes;
    }

    private static String readLine(InputStream input, int maxBytes) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        while (true) {
            int value = input.read();
            if (value < 0) {
                throw new IOException("http response ended inside a line");
            }
            if (value == '\r') {
                if (input.read() != '\n') {
                    throw new IOException("http response contained an invalid line ending");
                }
                return line.toString(StandardCharsets.ISO_8859_1);
            }
            line.write(value);
            if (line.size() > maxBytes) {
                throw new IOException("http response line exceeded the configured limit");
            }
        }
    }

    private static void requireCrLf(InputStream input) throws IOException {
        if (input.read() != '\r' || input.read() != '\n') {
            throw new IOException("http chunk contained an invalid line ending");
        }
    }

    private static int status(String statusLine) throws IOException {
        if (!statusLine.matches("HTTP/1\\.[01] [1-5][0-9]{2}(?: .*)?")) {
            throw new IOException("http response contained an invalid status line");
        }
        return Integer.parseInt(statusLine.substring(9, 12));
    }

    private static long parseLength(String value) throws IOException {
        try {
            long parsed = Long.parseLong(value.trim());
            if (parsed < 0 || parsed > Integer.MAX_VALUE) {
                throw new NumberFormatException("out of range");
            }
            return parsed;
        } catch (NumberFormatException failure) {
            throw new IOException("http response contained an invalid content-length", failure);
        }
    }

    private static String decode(byte[] bytes) throws IOException {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(bytes)).toString();
        } catch (java.nio.charset.CharacterCodingException failure) {
            throw new IOException("http response is not valid UTF-8", failure);
        }
    }

    private static void validate(URI target, InetAddress address,
                                 Map<String, String> headers,
                                 Duration timeout) {
        if (target == null || address == null || headers == null || timeout == null
                || target.getHost() == null
                || !("http".equalsIgnoreCase(target.getScheme())
                || "https".equalsIgnoreCase(target.getScheme()))) {
            throw new IllegalArgumentException("invalid pinned HTTP request");
        }
        if (timeout.isZero() || timeout.isNegative()
                || timeout.toMillis() > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("invalid HTTP timeout");
        }
    }

    private static String requestTarget(URI target) {
        String path = target.getRawPath();
        if (path == null || path.isEmpty()) {
            path = "/";
        }
        return target.getRawQuery() == null ? path : path + "?" + target.getRawQuery();
    }

    private static String hostHeader(URI target, int port) {
        String host = target.getHost().contains(":")
                ? "[" + target.getHost() + "]" : target.getHost();
        return port == defaultPort(target.getScheme()) ? host : host + ":" + port;
    }

    private static int effectivePort(URI target) {
        return target.getPort() >= 0 ? target.getPort() : defaultPort(target.getScheme());
    }

    private static int defaultPort(String scheme) {
        return "https".equalsIgnoreCase(scheme) ? 443 : 80;
    }

    private static boolean literalAddress(String host) {
        return host.matches("[0-9.]+") || host.contains(":");
    }

    private static long elapsedMs(long startNs) {
        return (System.nanoTime() - startNs) / 1_000_000L;
    }
}
