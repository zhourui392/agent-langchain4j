package com.anthropic.cclc.infrastructure.tools.governance;

import com.anthropic.cclc.domain.tool.ExecutionContext;
import com.anthropic.cclc.domain.tool.Tool;
import com.anthropic.cclc.domain.tool.ToolArguments;
import com.anthropic.cclc.domain.tool.ToolResult;

import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Applies timeout, redaction, and audit around a raw tool implementation.
 *
 * @author zhourui(V33215020)
 * @since 2026-06-11
 */
public final class GovernedTool implements Tool {

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
    public ToolResult execute(ToolArguments args, ExecutionContext ctx) {
        long startNs = System.nanoTime();
        ToolResult result = redact(runWithTimeout(args, ctx));
        long durationMs = (System.nanoTime() - startNs) / 1_000_000L;
        governance.auditSink().record(new ToolAuditEvent(
                delegate.name(), result.success(), durationMs, auditError(result)));
        return result;
    }

    private ToolResult runWithTimeout(ToolArguments args, ExecutionContext ctx) {
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<ToolResult> future = executor.submit(() -> delegate.execute(args, ctx));
            return await(future);
        }
    }

    private ToolResult await(Future<ToolResult> future) {
        try {
            return future.get(governance.timeout().toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException ex) {
            future.cancel(true);
            return ToolResult.error("tool timed out after " + governance.timeout().toMillis() + "ms");
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return ToolResult.error("tool interrupted");
        } catch (ExecutionException ex) {
            return ToolResult.error(executionFailureMessage(ex));
        }
    }

    private ToolResult redact(ToolResult result) {
        return new ToolResult(result.success(), governance.redactor().redact(result.content()));
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
