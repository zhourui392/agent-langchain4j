package com.anthropic.agentkit.infrastructure.tools.governance;

import com.anthropic.agentkit.domain.tool.ExecutionContext;
import com.anthropic.agentkit.domain.tool.Tool;
import com.anthropic.agentkit.domain.tool.ToolArguments;
import com.anthropic.agentkit.domain.tool.ToolResult;
import com.anthropic.agentkit.domain.tool.ToolResultStatus;
import com.anthropic.agentkit.domain.tool.ToolSafety;

import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Applies timeout, redaction, and audit around a raw tool implementation.
 *
 * @author zhourui(V33215020)
 * @since 2026-06-11
 */
public final class GovernedTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(GovernedTool.class);

    private final Tool delegate;
    private final ToolGovernance governance;

    public GovernedTool(Tool delegate, ToolGovernance governance) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.governance = Objects.requireNonNull(governance, "governance");
    }

    @Override
    public String name() {
        return delegate.name();
    }

    @Override
    public String description() {
        return delegate.description();
    }

    @Override
    public String inputSchema() {
        return delegate.inputSchema();
    }

    @Override
    public boolean isReadOnly() {
        return delegate.isReadOnly();
    }

    @Override
    public ToolSafety safety() {
        return delegate.safety();
    }

    @Override
    public ToolResult execute(ToolArguments args, ExecutionContext ctx) {
        long startNs = System.nanoTime();
        ToolResult result = redact(runWithTimeout(args, ctx));
        long durationMs = (System.nanoTime() - startNs) / 1_000_000L;
        if (!result.success()) {
            log.warn("governed tool finished with failure: tool={}, durationMs={}",
                    delegate.name(), durationMs);
        }
        governance.auditSink().record(new ToolAuditEvent(
                delegate.name(), result.success(), durationMs, auditError(result)));
        return result;
    }

    private ToolResult runWithTimeout(ToolArguments args, ExecutionContext ctx) {
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        try {
            Future<ToolResult> future = executor.submit(() -> delegate.execute(args, ctx));
            return await(future, ctx);
        } finally {
            executor.shutdownNow();
        }
    }

    private ToolResult await(Future<ToolResult> future, ExecutionContext context) {
        long timeoutNanos = minimumTimeoutNanos(context);
        try {
            return future.get(timeoutNanos, TimeUnit.NANOSECONDS);
        } catch (TimeoutException ex) {
            future.cancel(true);
            long timeoutMs = TimeUnit.NANOSECONDS.toMillis(timeoutNanos);
            log.warn("governed tool timed out: tool={}, timeoutMs={}", delegate.name(), timeoutMs);
            return ToolResult.of(ToolResultStatus.TIMEOUT,
                    "tool timed out after " + timeoutMs + "ms");
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            log.warn("governed tool interrupted: tool={}", delegate.name());
            return ToolResult.of(ToolResultStatus.CANCELLED, "tool interrupted");
        } catch (ExecutionException ex) {
            log.error("governed tool execution failed: tool={}", delegate.name(), ex);
            return ToolResult.error(executionFailureMessage(ex));
        }
    }

    private long minimumTimeoutNanos(ExecutionContext context) {
        long governanceNanos = governance.timeout().toNanos();
        long runNanos = context.limits().toolWait().toNanos();
        return Math.max(1, Math.min(governanceNanos, runNanos));
    }

    private ToolResult redact(ToolResult result) {
        return result.withContent(governance.redactor().redact(result.content()));
    }

    private static String auditError(ToolResult result) {
        return result.success() ? "" : result.content();
    }

    private static String executionFailureMessage(ExecutionException ex) {
        Throwable cause = ex.getCause();
        if (cause == null || cause.getMessage() == null) {
            return "tool execution failed";
        }
        return cause.getMessage();
    }
}
