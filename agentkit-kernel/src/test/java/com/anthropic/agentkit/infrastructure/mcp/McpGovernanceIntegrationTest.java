package com.anthropic.agentkit.infrastructure.mcp;

import ch.qos.logback.classic.Level;
import com.anthropic.agentkit.application.AgentExecutor;
import com.anthropic.agentkit.application.InteractivePrompter.UserPermissionResponse;
import com.anthropic.agentkit.application.PermissionService;
import com.anthropic.agentkit.domain.agent.AgentBudget;
import com.anthropic.agentkit.domain.agent.AgentRunContext;
import com.anthropic.agentkit.domain.agent.StopReason;
import com.anthropic.agentkit.domain.conversation.CancellationToken;
import com.anthropic.agentkit.domain.conversation.Conversation;
import com.anthropic.agentkit.domain.conversation.SessionId;
import com.anthropic.agentkit.domain.message.AiMessage;
import com.anthropic.agentkit.domain.message.ToolResultMessage;
import com.anthropic.agentkit.domain.message.UserMessage;
import com.anthropic.agentkit.domain.permission.PermissionMode;
import com.anthropic.agentkit.domain.port.RunEventStore;
import com.anthropic.agentkit.domain.run.RunEvent;
import com.anthropic.agentkit.domain.tool.ExecutionContext;
import com.anthropic.agentkit.domain.tool.ToolArguments;
import com.anthropic.agentkit.domain.tool.ToolRegistry;
import com.anthropic.agentkit.domain.tool.ToolResultStatus;
import com.anthropic.agentkit.domain.tool.ToolUseId;
import com.anthropic.agentkit.domain.tool.ToolUseRequest;
import com.anthropic.agentkit.infrastructure.permission.DefaultPermissionPolicy;
import com.anthropic.agentkit.testsupport.LogCapture;
import com.anthropic.agentkit.testsupport.StubLlmClient;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class McpGovernanceIntegrationTest {

    @Test
    void destructiveAnnotationStillRequiresLocalApprovalAndDeniedCallSettles() {
        RecordingSession session = new RecordingSession(destructiveTool());
        McpServerManager manager = manager(session);
        AtomicInteger prompts = new AtomicInteger();
        PermissionService permissions = new PermissionService(
                new DefaultPermissionPolicy(), (invocation, tool) -> {
                    prompts.incrementAndGet();
                    return UserPermissionResponse.DENY;
                }, PermissionMode.DEFAULT);
        StubLlmClient llm = new StubLlmClient()
                .enqueue(toolCall("inventory.delete"))
                .enqueue(AiMessage.text("denied"));
        Conversation conversation = conversation("delete inventory");

        var result = new AgentExecutor(
                llm, new ToolRegistry().registerCatalog(manager), permissions)
                .run(conversation, context(conversation)).join();

        assertThat(result.stopReason()).isEqualTo(StopReason.MODEL_COMPLETED);
        assertThat(prompts).hasValue(1);
        assertThat(session.callCount).isZero();
        assertThat(conversation.messages()).filteredOn(ToolResultMessage.class::isInstance)
                .singleElement().extracting(message -> ((ToolResultMessage) message).status())
                .isEqualTo(ToolResultStatus.DENIED);
        manager.close();
    }

    @Test
    void resolvedAuthenticationSecretNeverEntersPromptEventOrLogProjection() {
        String secret = "mcp-secret-never-project";
        AtomicReference<String> resolved = new AtomicReference<>();
        McpSessionFactory factory = (config, execution) -> {
            resolved.set(execution.secret("MCP_TOKEN").orElseThrow());
            return new RecordingSession(readTool());
        };
        McpServerConfig config = McpServerConfig.http(
                "inventory", URI.create("http://127.0.0.1/mcp"))
                .withSecretHeader("Authorization", "MCP_TOKEN");
        MemoryStore events = new MemoryStore();
        StubLlmClient llm = new StubLlmClient().enqueue(AiMessage.text("done"));
        Conversation conversation = conversation("inspect inventory");
        AgentRunContext context = context(conversation, secret);

        try (McpServerManager manager = new McpServerManager(List.of(config), factory);
             LogCapture logs = LogCapture.forClass(McpServerManager.class, Level.DEBUG)) {
            new AgentExecutor(llm, new ToolRegistry().registerCatalog(manager),
                    PermissionService.bypassing(), events)
                    .run(conversation, context).join();

            assertThat(resolved).hasValue(secret);
            assertThat(llm.capturedRequests().toString()).doesNotContain(secret);
            assertThat(events.items.toString()).doesNotContain(secret);
            assertThat(logs.events()).allSatisfy(event ->
                    assertThat(event.getFormattedMessage()).doesNotContain(secret));
        }
    }

    @Test
    void malformedRemoteResultBecomesSettledErrorAndRunCanContinue() {
        RecordingSession session = new RecordingSession(readTool());
        session.failure = new McpProtocolException("malformed tool result");
        MemoryStore events = new MemoryStore();
        StubLlmClient llm = new StubLlmClient()
                .enqueue(toolCall("inventory.read"))
                .enqueue(AiMessage.text("failure observed"));
        Conversation conversation = conversation("read inventory");

        try (McpServerManager manager = manager(session)) {
            var result = new AgentExecutor(
                    llm, new ToolRegistry().registerCatalog(manager),
                    PermissionService.bypassing(), events)
                    .run(conversation, context(conversation)).join();

            assertThat(result.stopReason()).isEqualTo(StopReason.MODEL_COMPLETED);
            assertThat(conversation.messages()).filteredOn(ToolResultMessage.class::isInstance)
                    .singleElement().satisfies(message -> assertThat((ToolResultMessage) message)
                            .extracting(ToolResultMessage::status)
                            .isEqualTo(ToolResultStatus.ERROR));
            assertThat(events.items).anyMatch(RunEvent.ToolInvocationSettled.class::isInstance);
        }
    }

    private static McpServerManager manager(RecordingSession session) {
        McpServerConfig config = McpServerConfig.stdio("inventory", List.of("unused"));
        return new McpServerManager(List.of(config), (ignored, context) -> session);
    }

    private static McpToolDescriptor destructiveTool() {
        return new McpToolDescriptor("delete", "Delete inventory", emptySchema(),
                McpToolAnnotations.destructive());
    }

    private static McpToolDescriptor readTool() {
        return new McpToolDescriptor("read", "Read inventory", emptySchema(),
                McpToolAnnotations.readOnly());
    }

    private static String emptySchema() {
        return "{\"type\":\"object\",\"properties\":{}}";
    }

    private static Conversation conversation(String text) {
        Conversation conversation = new Conversation(SessionId.fresh());
        conversation.append(UserMessage.of(text));
        return conversation;
    }

    private static AiMessage toolCall(String name) {
        return AiMessage.of("calling", List.of(new ToolUseRequest(
                new ToolUseId("mcp-call-1"), name, "{}")));
    }

    private static AgentRunContext context(Conversation conversation) {
        return AgentRunContext.create(conversation.sessionId(), Path.of("."),
                new CancellationToken(), AgentBudget.unlimited());
    }

    private static AgentRunContext context(Conversation conversation, String secret) {
        return AgentRunContext.create(conversation.sessionId(), Path.of("."),
                new CancellationToken(), AgentBudget.unlimited(),
                (scope, name) -> "MCP_TOKEN".equals(name)
                        ? java.util.Optional.of(secret) : java.util.Optional.empty());
    }

    private static final class RecordingSession implements McpSession {
        private final McpToolDescriptor descriptor;
        private int callCount;
        private RuntimeException failure;

        private RecordingSession(McpToolDescriptor descriptor) {
            this.descriptor = descriptor;
        }

        @Override
        public List<McpToolDescriptor> discoverTools() {
            return List.of(descriptor);
        }

        @Override
        public McpCallResult call(
                String toolName, ToolArguments arguments, ExecutionContext context) {
            callCount++;
            if (failure != null) {
                throw failure;
            }
            return McpCallResult.success("remote result");
        }

        @Override public void close() { }
    }

    private static final class MemoryStore implements RunEventStore {
        private final List<RunEvent> items = new ArrayList<>();

        @Override public void append(RunEvent event) { items.add(event); }

        @Override
        public List<RunEvent> load(com.anthropic.agentkit.domain.agent.RunId runId) {
            return items.stream().filter(event -> event.metadata().runId().equals(runId)).toList();
        }
    }
}
