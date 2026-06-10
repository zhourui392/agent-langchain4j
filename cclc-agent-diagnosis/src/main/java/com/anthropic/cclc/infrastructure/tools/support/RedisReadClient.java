package com.anthropic.cclc.infrastructure.tools.support;

import java.io.IOException;
import java.time.Duration;

/**
 * Read-only Redis access seam so {@code RedisReadTool} can be unit-tested without
 * a live server. The default {@link SocketRedisClient} speaks RESP over a raw
 * socket (no Redis client dependency).
 *
 * @author zhourui(V33215020)
 * @since 2026-06-08
 */
public interface RedisReadClient {

    String execute(String command, Duration timeout) throws IOException;
}
