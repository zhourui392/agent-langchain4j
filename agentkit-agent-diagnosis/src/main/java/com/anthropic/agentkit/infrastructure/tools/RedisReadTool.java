package com.anthropic.agentkit.infrastructure.tools;

import com.anthropic.agentkit.domain.tool.ExecutionContext;
import com.anthropic.agentkit.domain.tool.Tool;
import com.anthropic.agentkit.domain.tool.ToolArguments;
import com.anthropic.agentkit.domain.tool.ToolResult;
import com.anthropic.agentkit.infrastructure.tools.support.LogSanitizer;
import com.anthropic.agentkit.infrastructure.tools.support.RedisReadClient;

import java.io.IOException;
import java.time.Duration;
import java.util.Objects;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Read-only Redis tool. An allowlist of read commands (GET/TTL/TYPE/SCAN/...) is
 * the enforcement: anything not on it is rejected (default-deny).
 *
 * @author zhourui(V33215020)
 * @since 2026-06-08
 */
public final class RedisReadTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(RedisReadTool.class);

    private static final int DEFAULT_TIMEOUT_MS = 5_000;
    private static final Set<String> READ_COMMANDS = Set.of(
            "GET", "MGET", "STRLEN", "GETRANGE", "GETBIT", "BITCOUNT",
            "TYPE", "TTL", "PTTL", "EXPIRETIME", "PEXPIRETIME", "EXISTS",
            "SCAN", "RANDOMKEY", "DBSIZE",
            "HGET", "HMGET", "HGETALL", "HKEYS", "HVALS", "HLEN", "HEXISTS", "HSTRLEN",
            "LRANGE", "LLEN", "LINDEX", "LPOS",
            "SMEMBERS", "SCARD", "SISMEMBER", "SRANDMEMBER",
            "ZRANGE", "ZREVRANGE", "ZRANGEBYSCORE", "ZCARD", "ZSCORE", "ZRANK", "ZREVRANK", "ZCOUNT",
            "INFO", "PING", "OBJECT", "MEMORY");

    private final RedisReadClient client;

    public RedisReadTool(RedisReadClient client) {
        this.client = Objects.requireNonNull(client, "client");
    }

    @Override
    public String name() {
        return "RedisRead";
    }

    @Override
    public String description() {
        return "Read-only Redis: run one read command (GET/MGET/TYPE/TTL/SCAN/HGETALL/LRANGE/...).";
    }

    @Override
    public String inputSchema() {
        return "{\"type\":\"object\",\"properties\":{"
                + "\"command\":{\"type\":\"string\"},"
                + "\"timeoutMs\":{\"type\":\"integer\"}},"
                + "\"required\":[\"command\"]}";
    }

    @Override
    public boolean isReadOnly() {
        return true;
    }

    @Override
    public ToolResult execute(ToolArguments args, ExecutionContext ctx) {
        long startNs = System.nanoTime();
        String command = args.getString("command", "").trim();
        if (command.isEmpty()) {
            log.warn("redis read blocked: reason=missing_command");
            return ToolResult.error("RedisRead requires 'command'");
        }
        String verb = command.split("\\s+", 2)[0].toUpperCase();
        log.debug("redis read args: verb={}, command={}", verb, LogSanitizer.summarizeCommand(command));
        if (!READ_COMMANDS.contains(verb)) {
            log.warn("redis read blocked: verb={}", verb);
            return ToolResult.error("RedisRead permits only read-only commands (got '" + verb + "')");
        }
        Duration timeout = Duration.ofMillis(args.getInt("timeoutMs", DEFAULT_TIMEOUT_MS));
        try {
            String output = client.execute(command, timeout);
            log.info("redis read completed: verb={}, chars={}, durationMs={}",
                    verb, output.length(), elapsedMs(startNs));
            return ToolResult.ok(output);
        } catch (IOException ex) {
            log.error("redis read failed: verb={}, command={}", verb, LogSanitizer.summarizeCommand(command), ex);
            return ToolResult.error("RedisRead failed: " + ex.getMessage());
        }
    }

    private static long elapsedMs(long startNs) {
        return (System.nanoTime() - startNs) / 1_000_000L;
    }
}
