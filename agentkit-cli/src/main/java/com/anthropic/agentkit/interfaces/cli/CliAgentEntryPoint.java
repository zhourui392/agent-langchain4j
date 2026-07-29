package com.anthropic.agentkit.interfaces.cli;

import com.anthropic.agentkit.application.AgentExecutor;
import com.anthropic.agentkit.application.SystemPromptComposer;
import com.anthropic.agentkit.application.io.TerminalIo;
import com.anthropic.agentkit.domain.agent.AgentBudget;
import com.anthropic.agentkit.domain.agent.AgentEntryPoint;
import com.anthropic.agentkit.domain.agent.AgentRunContext;
import com.anthropic.agentkit.domain.agent.AgentRunResult;
import com.anthropic.agentkit.domain.conversation.CancellationToken;
import com.anthropic.agentkit.domain.conversation.Conversation;
import com.anthropic.agentkit.domain.port.SecretProvider;
import com.anthropic.agentkit.domain.suspension.ResumeCommand;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/** CLI-owned adapter around the permission-aware kernel executor. */
public final class CliAgentEntryPoint
        implements AgentEntryPoint<CliAgentRequest, CliAgentResult> {

    private final AgentExecutor executor;
    private final SystemPromptComposer composer;
    private final Path workspace;
    private final OutputRenderer renderer;
    private final RunSuspensionPrompter suspensionPrompter;
    private final SigintHandler sigint;
    private final SecretProvider secrets;

    public CliAgentEntryPoint(
            AgentExecutor executor, SystemPromptComposer composer, Path workspace,
            OutputRenderer renderer, TerminalIo terminal, SigintHandler sigint,
            SecretProvider secrets) {
        this.executor = Objects.requireNonNull(executor, "executor");
        this.composer = Objects.requireNonNull(composer, "composer");
        this.workspace = Objects.requireNonNull(workspace, "workspace");
        this.renderer = Objects.requireNonNull(renderer, "renderer");
        this.suspensionPrompter = new RunSuspensionPrompter(
                Objects.requireNonNull(terminal, "terminal"));
        this.sigint = Objects.requireNonNull(sigint, "sigint");
        this.secrets = Objects.requireNonNull(secrets, "secrets");
    }

    @Override
    public Class<CliAgentRequest> requestType() {
        return CliAgentRequest.class;
    }

    @Override
    public Class<CliAgentResult> resultType() {
        return CliAgentResult.class;
    }

    @Override
    public CliAgentResult invoke(CliAgentRequest request) {
        Objects.requireNonNull(request, "request");
        try {
            String prompt = composer.compose(workspace).full();
            AgentRunResult result = executeInitial(request.conversation(), prompt);
            return CliAgentResult.completed(resumeWhileRequested(
                    request.conversation(), prompt, result));
        } catch (RuntimeException failure) {
            renderer.onError(failure);
            return CliAgentResult.empty();
        }
    }

    private AgentRunResult executeInitial(Conversation conversation, String prompt) {
        return inFreshRunScope(conversation, context -> executor.run(
                conversation, context, renderer, prompt).join());
    }

    private AgentRunResult resumeWhileRequested(
            Conversation conversation, String prompt, AgentRunResult initial) {
        AgentRunResult result = initial;
        while (result.suspension().isPresent()) {
            Optional<ResumeCommand> command = suspensionPrompter.prompt(result);
            if (command.isEmpty()) {
                return result;
            }
            ResumeCommand selected = command.orElseThrow();
            result = inFreshRunScope(conversation, context -> executor.resume(
                    conversation, context, selected, renderer, prompt).join());
        }
        return result;
    }

    private AgentRunResult inFreshRunScope(
            Conversation conversation, Function<AgentRunContext, AgentRunResult> operation) {
        CancellationToken token = sigint.turnStarted();
        try {
            AgentRunContext context = AgentRunContext.create(
                    conversation.sessionId(), workspace, token,
                    AgentBudget.unlimited(), secrets);
            return operation.apply(context);
        } finally {
            sigint.turnFinished(token);
        }
    }
}
