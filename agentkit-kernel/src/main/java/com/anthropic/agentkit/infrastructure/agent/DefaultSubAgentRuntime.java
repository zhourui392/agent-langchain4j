package com.anthropic.agentkit.infrastructure.agent;

import com.anthropic.agentkit.application.PermissionService;
import com.anthropic.agentkit.application.interception.AgentInterceptors;
import com.anthropic.agentkit.domain.agent.AgentSpec;
import com.anthropic.agentkit.domain.agent.SubAgentExecutionScope;
import com.anthropic.agentkit.domain.agent.SubAgentHandle;
import com.anthropic.agentkit.domain.agent.SubAgentLimits;
import com.anthropic.agentkit.domain.agent.SubAgentRuntime;
import com.anthropic.agentkit.domain.agent.TerminalToolSpec;
import com.anthropic.agentkit.domain.agent.ToolCapabilitySet;
import com.anthropic.agentkit.domain.conversation.Conversation;
import com.anthropic.agentkit.domain.conversation.SessionId;
import com.anthropic.agentkit.domain.port.LlmClientSelector;
import com.anthropic.agentkit.domain.tool.ExecutionContext;
import com.anthropic.agentkit.domain.tool.ToolRegistry;
import com.anthropic.agentkit.infrastructure.tools.StructuredOutputTool;

import java.util.Objects;

/** In-process implementation of the bounded child-agent runtime contract. */
public final class DefaultSubAgentRuntime implements SubAgentRuntime {

    private final LlmClientSelector clients;
    private final ToolRegistry authorizedTools;
    private final PermissionService permissions;
    private final SubAgentLimits limits;
    private final SubAgentExecutionScope rootScope;
    private final AgentInterceptors interceptors;

    public DefaultSubAgentRuntime(
            LlmClientSelector clients, ToolRegistry authorizedTools,
            PermissionService permissions, SubAgentLimits limits) {
        this(clients, authorizedTools, permissions, limits, AgentInterceptors.none());
    }

    public DefaultSubAgentRuntime(
            LlmClientSelector clients, ToolRegistry authorizedTools,
            PermissionService permissions, SubAgentLimits limits,
            AgentInterceptors interceptors) {
        this.clients = Objects.requireNonNull(clients, "clients");
        this.authorizedTools = Objects.requireNonNull(authorizedTools, "authorizedTools");
        this.permissions = Objects.requireNonNull(permissions, "permissions");
        this.limits = Objects.requireNonNull(limits, "limits");
        this.interceptors = Objects.requireNonNull(interceptors, "interceptors");
        this.rootScope = SubAgentExecutionScope.root(limits);
    }

    @Override
    public SubAgentHandle spawn(
            AgentSpec spec, String task, ExecutionContext parentContext) {
        Objects.requireNonNull(spec, "spec");
        requireTask(task);
        Objects.requireNonNull(parentContext, "parentContext");
        validateCapabilities(spec.allowedTools());
        SubAgentExecutionScope scope = childScope(parentContext);
        SubAgentExecutionScope.Lease lease = scope.acquire();
        try {
            return startHandle(spec, task, parentContext, scope, lease);
        } catch (RuntimeException failure) {
            lease.close();
            throw failure;
        }
    }

    private SubAgentHandle startHandle(
            AgentSpec spec, String task, ExecutionContext parentContext,
            SubAgentExecutionScope scope, SubAgentExecutionScope.Lease lease) {
        Conversation conversation = new Conversation(SessionId.fresh());
        DefaultSubAgentHandle handle = new DefaultSubAgentHandle(
                spec, parentContext, conversation, buildTools(spec),
                clients, permissions, scope, interceptors);
        handle.start(task, lease);
        return handle;
    }

    private SubAgentExecutionScope childScope(ExecutionContext parent) {
        return parent.subAgentScope().orElse(rootScope).child(limits);
    }

    private void validateCapabilities(ToolCapabilitySet requested) {
        if (!authorizedTools.names().containsAll(requested.names())) {
            ToolCapabilitySet available = ToolCapabilitySet.copyOf(authorizedTools.names());
            throw new IllegalArgumentException(
                    "child tool capabilities exceed parent: requested="
                            + requested + ", parent=" + available);
        }
    }

    private ToolRegistry buildTools(AgentSpec spec) {
        ToolRegistry selected = new ToolRegistry();
        for (String name : spec.allowedTools().names()) {
            selected.register(authorizedTools.find(name));
        }
        spec.terminalTool().ifPresent(terminal -> registerTerminal(selected, terminal));
        return selected;
    }

    private static void registerTerminal(
            ToolRegistry registry, TerminalToolSpec terminal) {
        registry.register(new StructuredOutputTool(
                terminal.name(), terminal.description(), terminal.schema(), ignored -> { }));
    }

    private static void requireTask(String task) {
        if (task == null || task.isBlank()) {
            throw new IllegalArgumentException("sub-agent task must not be blank");
        }
    }
}
