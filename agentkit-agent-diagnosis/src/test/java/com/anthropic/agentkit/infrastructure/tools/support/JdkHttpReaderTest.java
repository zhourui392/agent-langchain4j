package com.anthropic.agentkit.infrastructure.tools.support;

import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * @author alex
 */
class JdkHttpReaderTest {

    @Test
    void connectsToPinnedAddressPreservesHostAndNeverFollowsRedirect() throws Exception {
        try (ServerSocket server = new ServerSocket(0, 1,
                InetAddress.getLoopbackAddress());
             var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var receivedHost = executor.submit(() -> {
                try (var socket = server.accept()) {
                    BufferedReader reader = new BufferedReader(new InputStreamReader(
                            socket.getInputStream(), StandardCharsets.US_ASCII));
                    String host = "";
                    String line;
                    while ((line = reader.readLine()) != null && !line.isEmpty()) {
                        if (line.regionMatches(true, 0, "Host:", 0, 5)) {
                            host = line.substring(5).trim();
                        }
                    }
                    socket.getOutputStream().write(("HTTP/1.1 302 Found\r\n"
                            + "Location: http://127.0.0.1/metadata\r\n"
                            + "Content-Length: 0\r\nConnection: close\r\n\r\n")
                            .getBytes(StandardCharsets.US_ASCII));
                    socket.getOutputStream().flush();
                    return host;
                }
            });
            URI target = URI.create("http://service.example:" + server.getLocalPort()
                    + "/health?brief=true");

            HttpReader.HttpResponseView response = new JdkHttpReader().getPinned(
                    target, InetAddress.getLoopbackAddress(), Map.of(), Duration.ofSeconds(2));

            assertThat(response.statusCode()).isEqualTo(302);
            assertThat(response.body()).isEmpty();
            assertThat(receivedHost.get()).isEqualTo(
                    "service.example:" + server.getLocalPort());
        }
    }
}
