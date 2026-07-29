package com.anthropic.agentkit.infrastructure.agent;

import com.anthropic.agentkit.application.AgentEventListener;
import com.anthropic.agentkit.application.AgentExecutor;
import com.anthropic.agentkit.application.PermissionService;
import com.anthropic.agentkit.domain.agent.AgentRunContext;
import com.anthropic.agentkit.domain.agent.AgentRunResult;
import com.anthropic.agentkit.domain.agent.AgentSpec;
import com.anthropic.agentkit.domain.agent.ModelPolicy;
import com.anthropic.agentkit.domain.agent.TerminalToolSpec;
import com.anthropic.agentkit.domain.conversation.Conversation;
import com.anthropic.agentkit.domain.message.UserMessage;
import com.anthropic.agentkit.domain.port.LlmClient;
import com.anthropic.agentkit.domain.port.LlmClientSelector;
import com.anthropic.agentkit.domain.port.RetrySleeper;
import com.anthropic.agentkit.domain.tool.Tool;
import com.anthropic.agentkit.domain.tool.ToolRegistry;
import com.anthropic.agentkit.infrastructure.tools.StructuredOutputTool;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/** Runs one schema-terminated role from an immutable {@link AgentSpec}. */
public final class StructuredAgent {

    private final LlmClientSelector clients;
    private final AgentSpec spec;
    private final List<Tool> domainTools;

    public StructuredAgent(LlmClient llm, AgentSpec spec, List<Tool> domainTools) {
        this(LlmClientSelector.fixed(llm), spec, domainTools);
    }

    public StructuredAgent(
            LlmClientSelector clients, AgentSpec spec, List<Tool> domainTools) {
        this.clients = Objects.requireNonNull(clients, "clients");
        this.spec = Objects.requireNonNull(spec, "spec");
        this.domainTools = List.copyOf(Objects.requireNonNull(domainTools, "domainTools"));
        requireTerminal(spec);
        validateCapabilities();
    }

    public Map<String, Object> run(String task, AgentRunContext context) {
        Objects.requireNonNull(task, "task");
        Objects.requireNonNull(context, "context");
        Conversation conversation = new Conversation(context.sessionId());
        conversation.append(UserMessage.of(task));
        AgentRunResult result = new AgentExecutor(
                clients, ModelPolicy.defaults(spec.modelTier()), RetrySleeper.system(),
                buildRegistry(), PermissionService.bypassing())
                .run(conversation, context, AgentEventListener.NO_OP, spec.systemPrompt())
                .join();
        return result.structuredOutput()
                .orElseThrow(() -> new StructuredOutputMissingException(terminal().name()));
    }

    private ToolRegistry buildRegistry() {
        ToolRegistry catalog = domainCatalog();
        ToolRegistry selected = new ToolRegistry();
        for (String name : spec.allowedTools().names()) {
            selected.register(catalog.find(name));
        }
        TerminalToolSpec terminal = terminal();
        selected.register(new StructuredOutputTool(
                terminal.name(), terminal.description(), terminal.schema(), ignored -> { }));
        return selected;
    }

    private ToolRegistry domainCatalog() {
        ToolRegistry catalog = new ToolRegistry();
        domainTools.forEach(catalog::register);
        return catalog;
    }

    private void validateCapabilities() {
        Set<String> available = domainTools.stream()
                .map(Tool::name)
                .collect(Collectors.toSet());
        if (!available.containsAll(spec.allowedTools().names())) {
            throw new IllegalArgumentException(
                    "AgentSpec references unavailable domain tools: " + spec.allowedTools());
        }
    }

    private TerminalToolSpec terminal() {
        return spec.terminalTool().orElseThrow();
    }

    private static void requireTerminal(AgentSpec spec) {
        if (spec.terminalTool().isEmpty()) {
            throw new IllegalArgumentException("structured agent requires a terminal tool");
        }
    }
}
