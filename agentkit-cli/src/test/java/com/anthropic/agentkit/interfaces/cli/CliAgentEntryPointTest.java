package com.anthropic.agentkit.interfaces.cli;

import com.anthropic.agentkit.application.AgentExecutor;
import com.anthropic.agentkit.application.PermissionService;
import com.anthropic.agentkit.application.SystemPromptComposer;
import com.anthropic.agentkit.application.agent.AgentRegistry;
import com.anthropic.agentkit.domain.agent.AgentId;
import com.anthropic.agentkit.domain.agent.AgentManifest;
import com.anthropic.agentkit.domain.conversation.Conversation;
import com.anthropic.agentkit.domain.conversation.SessionId;
import com.anthropic.agentkit.domain.message.AiMessage;
import com.anthropic.agentkit.domain.message.UserMessage;
import com.anthropic.agentkit.domain.port.RunEventStore;
import com.anthropic.agentkit.domain.port.SecretProvider;
import com.anthropic.agentkit.domain.run.RunEvent;
import com.anthropic.agentkit.domain.tool.ToolRegistry;
import com.anthropic.agentkit.testsupport.FakeTool;
import com.anthropic.agentkit.testsupport.StubLlmClient;
import com.anthropic.agentkit.testsupport.io.ScriptedTerminalIo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class CliAgentEntryPointTest {

    @Test
    void everyInvocationCreatesAFreshRunContextAndCancellationToken(@TempDir Path workspace) {
        StubLlmClient llm = new StubLlmClient()
                .enqueue(AiMessage.text("first"))
                .enqueue(AiMessage.text("second"));
        CapturingRunEventStore events = new CapturingRunEventStore();
        CliAgentEntryPoint entryPoint = entryPoint(llm, events, workspace, new SigintHandler(() -> {}));
        Conversation conversation = new Conversation(SessionId.of("cli-session"));
        conversation.append(UserMessage.of("one"));

        entryPoint.invoke(new CliAgentRequest(conversation));
        conversation.append(UserMessage.of("two"));
        entryPoint.invoke(new CliAgentRequest(conversation));

        List<RunEvent.RunStarted> starts = events.events.stream()
                .filter(RunEvent.RunStarted.class::isInstance)
                .map(RunEvent.RunStarted.class::cast)
                .toList();
        assertThat(starts).hasSize(2);
        assertThat(starts.get(0).metadata().runId())
                .isNotEqualTo(starts.get(1).metadata().runId());
    }

    @Test
    void assistantManifestUsesActualToolsAndCanBeSelectedFromRegistry(@TempDir Path workspace) {
        ToolRegistry tools = new ToolRegistry()
                .register(FakeTool.readOnlyReturning("Read", "body"));
        CliAgentEntryPoint entryPoint = entryPoint(
                new StubLlmClient().enqueue(AiMessage.text("done")),
                RunEventStore.none(), workspace, new SigintHandler(() -> {}));

        AgentManifest<CliAgentRequest, CliAgentResult> manifest =
                CliAgentManifest.create(entryPoint, tools);
        AgentRegistry registry = new AgentRegistry(List.of(manifest), Set.of());

        assertThat(manifest.id()).isEqualTo(AgentId.of("assistant"));
        assertThat(manifest.capabilities().allowedTools().names()).containsExactly("Read");
        assertThat(registry.select(AgentId.of("assistant"),
                CliAgentRequest.class, CliAgentResult.class)).isSameAs(entryPoint);
    }

    private static CliAgentEntryPoint entryPoint(
            StubLlmClient llm, RunEventStore events, Path workspace, SigintHandler sigint) {
        ScriptedTerminalIo terminal = ScriptedTerminalIo.builder().build();
        AgentExecutor executor = new AgentExecutor(
                llm, new ToolRegistry(), PermissionService.bypassing(), events);
        return new CliAgentEntryPoint(
                executor, new SystemPromptComposer("system", List.of()), workspace,
                new OutputRenderer(terminal), terminal, sigint, SecretProvider.none());
    }

    private static final class CapturingRunEventStore implements RunEventStore {
        private final List<RunEvent> events = new ArrayList<>();

        @Override
        public void append(RunEvent event) {
            events.add(event);
        }

        @Override
        public List<RunEvent> load(com.anthropic.agentkit.domain.agent.RunId runId) {
            return events.stream().filter(event -> event.metadata().runId().equals(runId)).toList();
        }
    }
}
