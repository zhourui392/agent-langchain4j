package com.anthropic.cclc.infrastructure.tools.support;

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

/**
 * {@link RedisReadClient} speaking RESP over a raw socket — no Redis client
 * dependency. Sends one command and renders the reply as text. Thin protocol
 * adapter, covered by integration rather than unit tests.
 *
 * @author zhourui(V33215020)
 * @since 2026-06-08
 */
public final class SocketRedisClient implements RedisReadClient {

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
        int timeoutMs = (int) Math.max(1, timeout.toMillis());
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), timeoutMs);
            socket.setSoTimeout(timeoutMs);
            OutputStream out = socket.getOutputStream();
            BufferedInputStream in = new BufferedInputStream(socket.getInputStream());
            authenticateIfNeeded(out, in);
            selectDatabaseIfNeeded(out, in);
            writeCommand(out, command.trim().split("\\s+"));
            return readReply(in);
        }
    }

    private void authenticateIfNeeded(OutputStream out, InputStream in) throws IOException {
        if (password != null && !password.isBlank()) {
            writeCommand(out, new String[]{"AUTH", password});
            readReply(in);
        }
    }

    private void selectDatabaseIfNeeded(OutputStream out, InputStream in) throws IOException {
        if (database > 0) {
            writeCommand(out, new String[]{"SELECT", Integer.toString(database)});
            readReply(in);
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

    private static String readReply(InputStream in) throws IOException {
        int prefix = in.read();
        if (prefix == -1) {
            throw new IOException("redis closed the connection");
        }
        return switch (prefix) {
            case '+', ':' -> readLine(in);
            case '-' -> "(error) " + readLine(in);
            case '$' -> readBulk(in);
            case '*' -> readArray(in);
            default -> throw new IOException("unexpected RESP prefix: " + (char) prefix);
        };
    }

    private static String readBulk(InputStream in) throws IOException {
        int length = Integer.parseInt(readLine(in));
        if (length < 0) {
            return "(nil)";
        }
        byte[] data = in.readNBytes(length);
        in.read();
        in.read();
        return new String(data, StandardCharsets.UTF_8);
    }

    private static String readArray(InputStream in) throws IOException {
        int count = Integer.parseInt(readLine(in));
        if (count < 0) {
            return "(nil)";
        }
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < count; i++) {
            if (i > 0) {
                out.append('\n');
            }
            out.append(readReply(in));
        }
        return out.toString();
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        int b;
        while ((b = in.read()) != -1) {
            if (b == '\r') {
                in.read();
                break;
            }
            buffer.write(b);
        }
        return buffer.toString(StandardCharsets.UTF_8);
    }
}
