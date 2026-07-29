package com.anthropic.agentkit.infrastructure.agent;

import com.anthropic.agentkit.application.AgentEventListener;
import com.anthropic.agentkit.application.AgentExecutor;
import com.anthropic.agentkit.domain.agent.AgentRunContext;
import com.anthropic.agentkit.domain.conversation.Conversation;
import com.anthropic.agentkit.domain.conversation.SessionId;
import com.anthropic.agentkit.domain.message.UserMessage;
import com.anthropic.agentkit.domain.port.LlmClient;
import com.anthropic.agentkit.domain.tool.Tool;
import com.anthropic.agentkit.domain.tool.ExecutionContext;
import com.anthropic.agentkit.domain.tool.ToolRegistry;
import com.anthropic.agentkit.infrastructure.tools.StructuredOutputTool;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Runs a constrained "expert agent" turn and returns its structured payload.
 *
 * <p>Captures the boilerplate every role used to repeat:
 * build a {@link StructuredOutputTool} into a fresh {@link ToolRegistry},
 * seed a {@link Conversation} with the task message, run an
 * {@link AgentExecutor}, and read back the sink. Returns a generic payload
 * ({@code Map<String,Object>}); domain VO mapping stays with the caller so
 * the kernel keeps no knowledge of any agent package's domain types.
 *
 * <p>Constructor parameters {@code (systemPrompt, output, domainTools)} together
 * materialize the role: a Planner gets no domain tools, a Coder gets read/write
 * tools plus its own terminal spec.
 *
 * @author zhourui(V33215020)
 * @since 2026-06-19
 */
public final class StructuredAgent {

    private final LlmClient llm;
    private final String systemPrompt;
    private final TerminalToolSpec output;
    private final List<Tool> domainTools;

    public StructuredAgent(LlmClient llm, String systemPrompt,
                           TerminalToolSpec output, List<Tool> domainTools) {
        this.llm = Objects.requireNonNull(llm, "llm");
        this.systemPrompt = Objects.requireNonNull(systemPrompt, "systemPrompt");
        this.output = Objects.requireNonNull(output, "output");
        this.domainTools = List.copyOf(Objects.requireNonNull(domainTools, "domainTools"));
    }

    public Map<String, Object> run(String task, AgentRunContext ctx) {
        Objects.requireNonNull(task, "task");
        Objects.requireNonNull(ctx, "ctx");
        AtomicReference<Map<String, Object>> sink = new AtomicReference<>();
        Conversation conversation = new Conversation(ctx.sessionId());
        conversation.append(UserMessage.of(task));
        new AgentExecutor(llm, buildRegistry(sink))
                .run(conversation, ctx, AgentEventListener.NO_OP, systemPrompt)
                .join();
        Map<String, Object> payload = sink.get();
        if (payload == null) {
            throw new StructuredOutputMissingException(output.name());
        }
        return payload;
    }

    /** @deprecated callers should provide the complete run scope. */
    @Deprecated
    public Map<String, Object> run(String task, ExecutionContext ctx) {
        Objects.requireNonNull(ctx, "ctx");
        AgentRunContext runContext = AgentRunContext.of(
                ctx.runId(), SessionId.fresh(), ctx.workspaceId(), ctx.cwd(),
                ctx.cancellation(), ctx.budget());
        return run(task, runContext);
    }

    private ToolRegistry buildRegistry(AtomicReference<Map<String, Object>> sink) {
        ToolRegistry registry = new ToolRegistry();
        domainTools.forEach(registry::register);
        registry.register(new StructuredOutputTool(
                output.name(), output.description(), output.schema(), sink::set));
        return registry;
    }
}
