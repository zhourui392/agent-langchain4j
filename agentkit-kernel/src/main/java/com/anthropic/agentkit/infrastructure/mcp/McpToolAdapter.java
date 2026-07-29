package com.anthropic.agentkit.infrastructure.mcp;

import com.anthropic.agentkit.domain.port.SecretScope;
import com.anthropic.agentkit.domain.tool.ExecutionContext;
import com.anthropic.agentkit.domain.tool.Tool;
import com.anthropic.agentkit.domain.tool.ToolArguments;
import com.anthropic.agentkit.domain.tool.ToolResult;
import com.anthropic.agentkit.domain.tool.ToolResultStatus;
import com.anthropic.agentkit.domain.tool.ToolSafety;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Adapts one namespaced remote declaration to the ordinary kernel Tool path. */
public final class McpToolAdapter implements Tool {

    private final String serverId;
    private final SecretScope boundScope;
    private final McpToolDescriptor descriptor;
    private final McpToolInvoker invoker;

    McpToolAdapter(String serverId, SecretScope boundScope,
                   McpToolDescriptor descriptor, McpToolInvoker invoker) {
        this.serverId = Objects.requireNonNull(serverId, "serverId");
        this.boundScope = Objects.requireNonNull(boundScope, "boundScope");
        this.descriptor = Objects.requireNonNull(descriptor, "descriptor");
        this.invoker = Objects.requireNonNull(invoker, "invoker");
    }

    @Override public String name() { return serverId + "." + descriptor.name(); }

    @Override public String description() { return descriptor.description(); }

    @Override public String inputSchema() { return descriptor.inputSchema(); }

    @Override
    public boolean isReadOnly() {
        return descriptor.annotations().permitsReadOnlyClassification();
    }

    @Override
    public ToolSafety safety() {
        McpToolAnnotations hints = descriptor.annotations();
        boolean readOnly = hints.permitsReadOnlyClassification();
        return new ToolSafety(readOnly, !readOnly && hints.destructiveHint(),
                hints.idempotentHint(), hints.openWorldHint());
    }

    @Override
    public ToolResult execute(ToolArguments arguments, ExecutionContext context) {
        if (!boundScope.equals(scopeOf(context))) {
            return failure(ToolResultStatus.ERROR, "MCP tool scope mismatch", "scope");
        }
        if (context.cancellation().isCancelled()) {
            return failure(ToolResultStatus.CANCELLED, "MCP tool cancelled", "cancel");
        }
        try {
            McpCallResult result = Objects.requireNonNull(
                    invoker.invoke(descriptor, arguments, context),
                    "MCP invoker returned null");
            ToolResultStatus status = result.error()
                    ? ToolResultStatus.ERROR : ToolResultStatus.SUCCESS;
            return ToolResult.of(status, result.content(), metadata(result.metadata(), "call"));
        } catch (McpTimeoutException failure) {
            return failure(ToolResultStatus.TIMEOUT, "MCP tool timed out", "timeout");
        } catch (McpCancelledException failure) {
            return failure(ToolResultStatus.CANCELLED, "MCP tool cancelled", "cancel");
        } catch (McpProtocolException failure) {
            return failure(ToolResultStatus.ERROR, "invalid MCP tool result", "protocol");
        } catch (McpConnectionException failure) {
            return failure(ToolResultStatus.ERROR, "MCP connection failed", "transport");
        } catch (RuntimeException failure) {
            return failure(ToolResultStatus.ERROR, "MCP tool execution failed", "adapter");
        }
    }

    private ToolResult failure(ToolResultStatus status, String content, String stage) {
        return ToolResult.of(status, content, metadata(Map.of(), stage));
    }

    private Map<String, String> metadata(Map<String, String> source, String stage) {
        Map<String, String> metadata = new LinkedHashMap<>(source);
        metadata.put("mcp.server", serverId);
        metadata.put("mcp.tool", descriptor.name());
        metadata.put("stage", "mcp_" + stage);
        return Map.copyOf(metadata);
    }

    private static SecretScope scopeOf(ExecutionContext context) {
        Objects.requireNonNull(context, "context");
        return new SecretScope(context.runId(), context.workspaceId());
    }
}
