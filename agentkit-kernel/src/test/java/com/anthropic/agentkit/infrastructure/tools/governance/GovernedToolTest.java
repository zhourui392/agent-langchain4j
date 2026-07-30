package com.anthropic.agentkit.infrastructure.tools.governance;

import com.anthropic.agentkit.domain.tool.ExecutionContext;
import com.anthropic.agentkit.domain.tool.Tool;
import com.anthropic.agentkit.domain.tool.ToolArguments;
import com.anthropic.agentkit.domain.tool.ToolResult;
import org.junit.jupiter.api.Test;

import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class GovernedToolTest {

    private final List<ToolAuditEvent> events = new ArrayList<>();

    @Test
    void redactsReturnedContentAndAuditsSuccess() {
        Tool raw = toolReturning(ToolResult.ok("phone=13812345678"));
        GovernedTool tool = new GovernedTool(raw, new ToolGovernance(
                Duration.ofSeconds(1),
                content -> content.replaceAll("138\\d{8}", "***"),
                events::add));

        ExecutionContext executionContext = context();
        ToolResult result = tool.execute(ToolArguments.empty(), executionContext);

        assertThat(result.success()).isTrue();
        assertThat(result.content()).isEqualTo("phone=***");
        assertThat(events).singleElement().satisfies(event -> {
            assertThat(event.toolName()).isEqualTo("Raw");
            assertThat(event.success()).isTrue();
            assertThat(event.durationMs()).isGreaterThanOrEqualTo(0);
            assertThat(event.runId()).isEqualTo(executionContext.runId().value());
            assertThat(event.sessionId()).isEqualTo(executionContext.sessionId().value());
            assertThat(event.resultBytes()).isEqualTo("phone=***".getBytes(
                    java.nio.charset.StandardCharsets.UTF_8).length);
        });
    }

    @Test
    void returnsStructuredErrorAndAuditsTimeout() {
        Tool slow = slowTool(Duration.ofMillis(200));
        GovernedTool tool = new GovernedTool(slow, new ToolGovernance(
                Duration.ofMillis(10),
                ToolRedactor.NO_OP,
                events::add));

        ToolResult result = tool.execute(ToolArguments.empty(), context());

        assertThat(result.success()).isFalse();
        assertThat(result.content()).contains("timed out");
        assertThat(events).singleElement().satisfies(event -> {
            assertThat(event.success()).isFalse();
            assertThat(event.error()).contains("timed out");
        });
    }

    @Test
    void rateLimitDeniesBeforeDelegateAndEmitsTypedAuditResult() {
        int[] calls = {0};
        Tool raw = toolReturning(ToolResult.ok("must-not-run"));
        Tool counting = new Tool() {
            @Override public String name() { return raw.name(); }
            @Override public String description() { return raw.description(); }
            @Override public String inputSchema() { return raw.inputSchema(); }
            @Override public boolean isReadOnly() { return raw.isReadOnly(); }
            @Override public ToolResult execute(ToolArguments args, ExecutionContext ctx) {
                calls[0]++;
                return raw.execute(args, ctx);
            }
        };
        GovernedTool tool = new GovernedTool(counting, new ToolGovernance(
                Duration.ofSeconds(1), ToolRedactor.NO_OP, events::add,
                (toolName, context) -> false));

        ToolResult result = tool.execute(ToolArguments.empty(), context());

        assertThat(result.success()).isFalse();
        assertThat(result.metadata()).containsEntry("diagnosis.errorCode", "RATE_LIMITED");
        assertThat(calls[0]).isZero();
        assertThat(events).singleElement().satisfies(event -> {
            assertThat(event.success()).isFalse();
            assertThat(event.error()).contains("rate limit");
        });
    }

    private static Tool toolReturning(ToolResult result) {
        return new Tool() {
            @Override public String name() { return "Raw"; }
            @Override public String description() { return "raw tool"; }
            @Override public String inputSchema() { return "{\"type\":\"object\"}"; }
            @Override public boolean isReadOnly() { return true; }
            @Override public ToolResult execute(ToolArguments args, ExecutionContext ctx) { return result; }
        };
    }

    private static Tool slowTool(Duration duration) {
        return new Tool() {
            @Override public String name() { return "Slow"; }
            @Override public String description() { return "slow tool"; }
            @Override public String inputSchema() { return "{\"type\":\"object\"}"; }
            @Override public boolean isReadOnly() { return true; }

            @Override
            public ToolResult execute(ToolArguments args, ExecutionContext ctx) {
                try {
                    Thread.sleep(duration.toMillis());
                    return ToolResult.ok("late");
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    return ToolResult.error("interrupted");
                }
            }
        };
    }

    private static ExecutionContext context() {
        return ExecutionContext.at(Paths.get(System.getProperty("user.dir")));
    }
}
