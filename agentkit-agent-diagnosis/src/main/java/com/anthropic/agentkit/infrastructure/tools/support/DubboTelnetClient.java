package com.anthropic.agentkit.infrastructure.tools.support;

import java.io.IOException;
import java.time.Duration;

/**
 * Dubbo telnet invocation seam so {@code DubboInvokeTool} can be unit-tested
 * without a live provider. The default {@link SocketDubboTelnetClient} speaks the
 * telnet {@code invoke} protocol over a raw socket (no Dubbo dependency).
 *
 * @author zhourui(V33215020)
 * @since 2026-06-08
 */
public interface DubboTelnetClient {

    String invoke(String address, String invocation, Duration timeout) throws IOException;
}
