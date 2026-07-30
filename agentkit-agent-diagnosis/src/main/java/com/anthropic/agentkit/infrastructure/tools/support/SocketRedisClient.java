package com.anthropic.agentkit.infrastructure.tools.support;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link RedisReadClient} speaking RESP over a raw socket — no Redis client
 * dependency. Sends one command and renders the reply as text. Thin protocol
 * adapter, covered by integration rather than unit tests.
 *
 * @author zhourui(V33215020)
 * @since 2026-06-08
 */
public final class SocketRedisClient implements RedisReadClient {

    private static final Logger log = LoggerFactory.getLogger(SocketRedisClient.class);
    private static final int MAX_BULK_BYTES = 1024 * 1024;
    private static final int MAX_ARRAY_ITEMS = 10_000;
    private static final int MAX_NESTING_DEPTH = 16;
    private static final int MAX_LINE_BYTES = 64 * 1024;
    private static final byte[] TRUNCATION_MARKER =
            "\n...<truncated>...".getBytes(StandardCharsets.UTF_8);

    private final String host;
    private final int port;
    private final String password;
    private final int database;

    public SocketRedisClient(String host, int port, String password, int database) {
        this.host = Objects.requireNonNull(host, "host");
        this.port = port;
        this.password = password;
        this.database = database;
    }

    @Override
    public String execute(String command, Duration timeout) throws IOException {
        long startNs = System.nanoTime();
        int timeoutMs = (int) Math.max(1, timeout.toMillis());
        log.debug("redis socket connecting: host={}, port={}, database={}, timeoutMs={}, command={}",
                host, port, database, timeoutMs, LogSanitizer.summarizeCommand(command));
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), timeoutMs);
            socket.setSoTimeout(timeoutMs);
            log.debug("redis socket connected: host={}, port={}", host, port);
            OutputStream out = socket.getOutputStream();
            BufferedInputStream in = new BufferedInputStream(socket.getInputStream());
            authenticateIfNeeded(out, in);
            selectDatabaseIfNeeded(out, in);
            writeCommand(out, command.trim().split("\\s+"));
            String reply = renderReply(in);
            log.debug("redis socket command completed: host={}, port={}, bytes={}, durationMs={}",
                    host, port, reply.getBytes(StandardCharsets.UTF_8).length, elapsedMs(startNs));
            return reply;
        } catch (IOException ex) {
            log.error("redis socket command failed: host={}, port={}, command={}",
                    host, port, LogSanitizer.summarizeCommand(command), ex);
            throw ex;
        }
    }

    private void authenticateIfNeeded(OutputStream out, InputStream in) throws IOException {
        if (password != null && !password.isBlank()) {
            log.debug("redis socket authenticating: host={}, port={}", host, port);
            writeCommand(out, new String[]{"AUTH", password});
            renderReply(in);
        }
    }

    private void selectDatabaseIfNeeded(OutputStream out, InputStream in) throws IOException {
        if (database > 0) {
            log.debug("redis socket selecting database: host={}, port={}, database={}", host, port, database);
            writeCommand(out, new String[]{"SELECT", Integer.toString(database)});
            renderReply(in);
        }
    }

    private static void writeCommand(OutputStream out, String[] args) throws IOException {
        StringBuilder request = new StringBuilder().append('*').append(args.length).append("\r\n");
        for (String arg : args) {
            int byteLength = arg.getBytes(StandardCharsets.UTF_8).length;
            request.append('$').append(byteLength).append("\r\n").append(arg).append("\r\n");
        }
        out.write(request.toString().getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    static String renderReply(InputStream in) throws IOException {
        return readReply(in, 0);
    }

    private static String readReply(InputStream in, int depth) throws IOException {
        if (depth > MAX_NESTING_DEPTH) {
            throw new IOException("redis reply nesting exceeded the configured limit");
        }
        int prefix = in.read();
        if (prefix == -1) {
            throw new IOException("redis closed the connection");
        }
        return switch (prefix) {
            case '+', ':' -> readLine(in);
            case '-' -> "(error) " + readLine(in);
            case '$' -> readBulk(in);
            case '*' -> readArray(in, depth + 1);
            default -> throw new IOException("unexpected RESP prefix: " + (char) prefix);
        };
    }

    private static String readBulk(InputStream in) throws IOException {
        int length = Integer.parseInt(readLine(in));
        if (length < 0) {
            return "(nil)";
        }
        int retained = Math.min(length, MAX_BULK_BYTES - TRUNCATION_MARKER.length);
        byte[] data = in.readNBytes(retained);
        if (data.length != retained) {
            throw new IOException("redis bulk reply ended early");
        }
        if (length > retained) {
            in.skipNBytes(length - retained);
        }
        requireCrLf(in);
        if (length <= retained) {
            return new String(data, StandardCharsets.UTF_8);
        }
        ByteArrayOutputStream bounded = new ByteArrayOutputStream(MAX_BULK_BYTES);
        bounded.writeBytes(data);
        bounded.writeBytes(TRUNCATION_MARKER);
        return bounded.toString(StandardCharsets.UTF_8);
    }

    private static String readArray(InputStream in, int depth) throws IOException {
        int count = Integer.parseInt(readLine(in));
        if (count < 0) {
            return "(nil)";
        }
        if (count > MAX_ARRAY_ITEMS) {
            throw new IOException("redis array reply exceeded the configured item limit");
        }
        StringBuilder out = new StringBuilder();
        int outputBytes = 0;
        for (int i = 0; i < count; i++) {
            int separatorBytes = i > 0 ? 1 : 0;
            String item = readReply(in, depth);
            int itemBytes = item.getBytes(StandardCharsets.UTF_8).length;
            if (outputBytes + separatorBytes + itemBytes > MAX_BULK_BYTES) {
                throw new IOException("redis array reply exceeded the configured byte limit");
            }
            if (i > 0) {
                out.append('\n');
            }
            out.append(item);
            outputBytes += separatorBytes + itemBytes;
        }
        return out.toString();
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        int b;
        while ((b = in.read()) != -1) {
            if (b == '\r') {
                if (in.read() != '\n') {
                    throw new IOException("redis reply contained an invalid line ending");
                }
                break;
            }
            buffer.write(b);
            if (buffer.size() > MAX_LINE_BYTES) {
                throw new IOException("redis reply line exceeded the configured limit");
            }
        }
        return buffer.toString(StandardCharsets.UTF_8);
    }

    private static void requireCrLf(InputStream in) throws IOException {
        if (in.read() != '\r' || in.read() != '\n') {
            throw new IOException("redis bulk reply contained an invalid line ending");
        }
    }

    private static long elapsedMs(long startNs) {
        return (System.nanoTime() - startNs) / 1_000_000L;
    }
}
