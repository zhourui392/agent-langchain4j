package com.anthropic.cclc.infrastructure.tools;

import com.anthropic.cclc.domain.tool.ExecutionContext;
import com.anthropic.cclc.domain.tool.Tool;
import com.anthropic.cclc.domain.tool.ToolArguments;
import com.anthropic.cclc.domain.tool.ToolResult;
import com.anthropic.cclc.infrastructure.tools.support.DubboTelnetClient;

import java.util.Objects;

/**
 * Read-only Dubbo telnet invoke. Because {@code invoke} can reach any method, a
 * method-name guard permits only read-shaped methods (get/query/list/...). Stub
 * for Red.
 *
 * @author zhourui(V33215020)
 * @since 2026-06-08
 */
public final class DubboInvokeTool implements Tool {

    private final DubboTelnetClient client;

    public DubboInvokeTool(DubboTelnetClient client) {
        this.client = Objects.requireNonNull(client, "client");
    }

    @Override
    public String name() {
        return "DubboInvoke";
    }

    @Override
    public String description() {
        return "Read-only Dubbo telnet invoke against a provider address; "
                + "only read-shaped methods (get/query/list/find/count/...) are permitted.";
    }

    @Override
    public String inputSchema() {
        return "{\"type\":\"object\",\"properties\":{"
                + "\"address\":{\"type\":\"string\"},"
                + "\"invocation\":{\"type\":\"string\"},"
                + "\"timeoutMs\":{\"type\":\"integer\"}},"
                + "\"required\":[\"address\",\"invocation\"]}";
    }

    @Override
    public boolean isReadOnly() {
        return true;
    }

    @Override
    public ToolResult execute(ToolArguments args, ExecutionContext ctx) {
        return ToolResult.error("not implemented");
    }
}
