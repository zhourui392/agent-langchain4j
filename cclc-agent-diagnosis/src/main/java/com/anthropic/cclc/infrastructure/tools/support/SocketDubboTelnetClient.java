package com.anthropic.cclc.infrastructure.tools.support;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link DubboTelnetClient} over a raw socket speaking the Dubbo telnet
 * {@code invoke} protocol — no Dubbo dependency. Thin protocol adapter, covered
 * by integration rather than unit tests.
 *
 * @author zhourui(V33215020)
 * @since 2026-06-08
 */
public final class SocketDubboTelnetClient implements DubboTelnetClient {

    private static final Logger log = LoggerFactory.getLogger(SocketDubboTelnetClient.class);
    private static final String PROMPT = "dubbo>";

    @Override
    public String invoke(String address, String invocation, Duration timeout) throws IOException {
        long startNs = System.nanoTime();
        HostPort hostPort = HostPort.parse(address);
        int timeoutMs = (int) Math.max(1, timeout.toMillis());
        log.debug("dubbo telnet connecting: host={}, port={}, timeoutMs={}, invocation={}",
                hostPort.host(), hostPort.port(), timeoutMs, LogSanitizer.truncate(invocation, 120));
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(hostPort.host(), hostPort.port()), timeoutMs);
            socket.setSoTimeout(timeoutMs);
            log.debug("dubbo telnet connected: host={}, port={}", hostPort.host(), hostPort.port());
            sendInvoke(socket.getOutputStream(), invocation);
            String response = readUntilPrompt(socket.getInputStream());
            log.debug("dubbo telnet invoke completed: host={}, port={}, bytes={}, durationMs={}",
                    hostPort.host(), hostPort.port(), response.getBytes(StandardCharsets.UTF_8).length,
                    elapsedMs(startNs));
            return response;
        } catch (IOException ex) {
            log.error("dubbo telnet invoke failed: host={}, port={}, invocation={}",
                    hostPort.host(), hostPort.port(), LogSanitizer.truncate(invocation, 120), ex);
            throw ex;
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

    private static long elapsedMs(long startNs) {
        return (System.nanoTime() - startNs) / 1_000_000L;
    }
}
