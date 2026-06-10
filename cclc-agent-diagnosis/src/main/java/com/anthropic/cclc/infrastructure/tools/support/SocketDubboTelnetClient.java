package com.anthropic.cclc.infrastructure.tools.support;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * {@link DubboTelnetClient} over a raw socket speaking the Dubbo telnet
 * {@code invoke} protocol — no Dubbo dependency. Thin protocol adapter, covered
 * by integration rather than unit tests.
 *
 * @author zhourui(V33215020)
 * @since 2026-06-08
 */
public final class SocketDubboTelnetClient implements DubboTelnetClient {

    private static final String PROMPT = "dubbo>";

    @Override
    public String invoke(String address, String invocation, Duration timeout) throws IOException {
        HostPort hostPort = HostPort.parse(address);
        int timeoutMs = (int) Math.max(1, timeout.toMillis());
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(hostPort.host(), hostPort.port()), timeoutMs);
            socket.setSoTimeout(timeoutMs);
            sendInvoke(socket.getOutputStream(), invocation);
            return readUntilPrompt(socket.getInputStream());
        }
    }

    private static void sendInvoke(OutputStream out, String invocation) throws IOException {
        out.write(("invoke " + invocation + "\r\n").getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    private static String readUntilPrompt(InputStream in) throws IOException {
        StringBuilder buffer = new StringBuilder();
        byte[] chunk = new byte[1024];
        int read;
        while ((read = in.read(chunk)) != -1) {
            buffer.append(new String(chunk, 0, read, StandardCharsets.UTF_8));
            if (buffer.indexOf(PROMPT) >= 0) {
                break;
            }
        }
        return stripPrompt(buffer.toString());
    }

    private static String stripPrompt(String response) {
        int prompt = response.lastIndexOf(PROMPT);
        return (prompt >= 0 ? response.substring(0, prompt) : response).trim();
    }

    private record HostPort(String host, int port) {
        static HostPort parse(String address) throws IOException {
            int colon = address.lastIndexOf(':');
            if (colon <= 0 || colon == address.length() - 1) {
                throw new IOException("invalid dubbo address (want host:port): " + address);
            }
            try {
                return new HostPort(address.substring(0, colon),
                        Integer.parseInt(address.substring(colon + 1)));
            } catch (NumberFormatException ex) {
                throw new IOException("invalid dubbo port in address: " + address, ex);
            }
        }
    }
}
