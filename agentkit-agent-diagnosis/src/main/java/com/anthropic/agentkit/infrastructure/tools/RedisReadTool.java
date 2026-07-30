package com.anthropic.agentkit.infrastructure.tools;

import com.anthropic.agentkit.domain.tool.ExecutionContext;
import com.anthropic.agentkit.domain.tool.Tool;
import com.anthropic.agentkit.domain.tool.ToolArguments;
import com.anthropic.agentkit.domain.tool.ToolResult;
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
    private static final int MAX_TIMEOUT_MS = 10_000;
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
    private final Set<String> allowedKeyPrefixes;
    private final boolean strictKeyScope;

    public RedisReadTool(RedisReadClient client) {
        this(client, Set.of(), false);
    }

    public RedisReadTool(RedisReadClient client, Set<String> allowedKeyPrefixes) {
        this(client, allowedKeyPrefixes, true);
    }

    private RedisReadTool(RedisReadClient client, Set<String> allowedKeyPrefixes,
                          boolean strictKeyScope) {
        this.client = Objects.requireNonNull(client, "client");
        this.allowedKeyPrefixes = cleanPrefixes(allowedKeyPrefixes);
        this.strictKeyScope = strictKeyScope;
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
        if (command.isEmpty() || command.length() > 4096
                || command.chars().anyMatch(Character::isISOControl)) {
            log.warn("redis read blocked: reason=missing_command");
            return ToolResult.error("RedisRead requires 'command'");
        }
        String verb = command.split("\\s+", 2)[0].toUpperCase();
        log.debug("redis read args: verb={}", verb);
        if (!READ_COMMANDS.contains(verb)) {
            log.warn("redis read blocked: verb={}", verb);
            return ToolResult.error("RedisRead permits only read-only commands (got '" + verb + "')");
        }
        if (strictKeyScope && !withinKeyScope(command)) {
            log.warn("redis read blocked: reason=key_scope, verb={}", verb);
            return ToolResult.error("RedisRead key or scan pattern is outside the allowed prefix");
        }
        int timeoutMs = args.getInt("timeoutMs", DEFAULT_TIMEOUT_MS);
        if (timeoutMs <= 0 || timeoutMs > MAX_TIMEOUT_MS) {
            return ToolResult.error("RedisRead timeoutMs is outside the allowed range");
        }
        Duration timeout = Duration.ofMillis(timeoutMs);
        try {
            String output = client.execute(command, timeout);
            log.info("redis read completed: verb={}, chars={}, durationMs={}",
                    verb, output.length(), elapsedMs(startNs));
            return ToolResult.ok(output);
        } catch (IOException ex) {
            log.error("redis read failed: verb={}, failureType={}",
                    verb, ex.getClass().getSimpleName());
            return ToolResult.error("RedisRead failed: backend request could not be completed");
        }
    }

    private boolean withinKeyScope(String command) {
        if (allowedKeyPrefixes.isEmpty()) {
            return false;
        }
        String[] tokens = command.split("\\s+");
        if (tokens.length < 2 || tokens.length > 100) {
            return false;
        }
        String verb = tokens[0].toUpperCase();
        if (verb.equals("SCAN")) {
            return validScan(tokens);
        }
        int lastKey = verb.equals("MGET") || verb.equals("EXISTS") ? tokens.length : 2;
        if (lastKey <= 1) {
            return false;
        }
        for (int index = 1; index < lastKey; index++) {
            if (!allowedKey(tokens[index])) {
                return false;
            }
        }
        return !Set.of("RANDOMKEY", "DBSIZE", "INFO", "PING").contains(verb);
    }

    private boolean validScan(String[] tokens) {
        if (!tokens[1].matches("\\d+")) {
            return false;
        }
        String pattern = option(tokens, "MATCH");
        String count = option(tokens, "COUNT");
        if (pattern == null || count == null || !count.matches("\\d+") || count.length() > 4) {
            return false;
        }
        int requested;
        try {
            requested = Integer.parseInt(count);
        } catch (NumberFormatException invalidCount) {
            return false;
        }
        String literal = pattern.substring(0, wildcardIndex(pattern));
        return requested > 0 && requested <= 1000 && allowedKey(literal);
    }

    private String option(String[] tokens, String name) {
        for (int index = 2; index + 1 < tokens.length; index += 2) {
            if (name.equalsIgnoreCase(tokens[index])) {
                return tokens[index + 1];
            }
        }
        return null;
    }

    private int wildcardIndex(String value) {
        int index = value.length();
        for (char wildcard : new char[]{'*', '?', '['}) {
            int found = value.indexOf(wildcard);
            if (found >= 0) {
                index = Math.min(index, found);
            }
        }
        return index;
    }

    private boolean allowedKey(String key) {
        return allowedKeyPrefixes.stream().anyMatch(key::startsWith);
    }

    private static Set<String> cleanPrefixes(Set<String> values) {
        if (values == null) {
            return Set.of();
        }
        return values.stream().map(value -> Objects.requireNonNull(value, "key prefix").trim())
                .filter(value -> !value.isEmpty())
                .peek(value -> {
                    if (value.chars().anyMatch(Character::isISOControl)) {
                        throw new IllegalArgumentException("Redis key prefix is invalid");
                    }
                }).collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    private static long elapsedMs(long startNs) {
        return (System.nanoTime() - startNs) / 1_000_000L;
    }
}
