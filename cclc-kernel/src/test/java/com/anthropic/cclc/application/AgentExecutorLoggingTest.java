package com.anthropic.cclc.application;

import ch.qos.logback.classic.Level;
import com.anthropic.cclc.domain.conversation.CancellationToken;
import com.anthropic.cclc.domain.conversation.Conversation;
import com.anthropic.cclc.domain.conversation.SessionId;
import com.anthropic.cclc.domain.message.AiMessage;
import com.anthropic.cclc.domain.message.UserMessage;
import com.anthropic.cclc.domain.tool.ToolRegistry;
import com.anthropic.cclc.domain.tool.ToolResult;
import com.anthropic.cclc.domain.tool.ToolUseId;
import com.anthropic.cclc.domain.tool.ToolUseRequest;
import com.anthropic.cclc.testsupport.FakeTool;
import com.anthropic.cclc.testsupport.LogCapture;
import com.anthropic.cclc.testsupport.StubLlmClient;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AgentExecutorLoggingTest {

    private static final Logger TOOL_LOG = LoggerFactory.getLogger("test.tool.mdc");

    @Test
    void should_WriteSessionAndTurnMdc_When_RunningLlmTurn() {
        StubLlmClient stub = new StubLlmClient().enqueue(AiMessage.text("hello"));
        Conversation conversation = new Conversation(SessionId.of("session-logs"));
        conversation.append(UserMessage.of("say hi"));

        try (LogCapture logs = LogCapture.forClass(AgentExecutor.class, Level.INFO)) {
            new AgentExecutor(stub, new ToolRegistry())
                    .run(conversation, new CancellationToken())
                    .join();

            assertThat(logs.events()).anySatisfy(event -> {
                assertThat(event.getFormattedMessage()).contains("session started");
                assertThat(event.getMDCPropertyMap()).containsEntry("session", "session-logs");
            });
            assertThat(logs.events()).anySatisfy(event -> {
                assertThat(event.getFormattedMessage()).contains("turn 1 started");
                assertThat(event.getMDCPropertyMap())
                        .containsEntry("session", "session-logs")
                        .containsEntry("turn", "1");
            });
        }
    }

    @Test
    void should_PropagateMdcToParallelToolThreads_When_DispatchingToolBatch() {
        ToolUseId firstId = new ToolUseId("u1");
        ToolUseId secondId = new ToolUseId("u2");
        StubLlmClient stub = new StubLlmClient()
                .enqueue(AiMessage.of("", List.of(
                        new ToolUseRequest(firstId, "First", "{}"),
                        new ToolUseRequest(secondId, "Second", "{}"))))
                .enqueue(AiMessage.text("done"));
        ToolRegistry tools = new ToolRegistry()
                .register(FakeTool.withBehavior("First", args -> {
                    TOOL_LOG.info("tool mdc checkpoint");
                    return ToolResult.ok("first");
                }))
                .register(FakeTool.withBehavior("Second", args -> {
                    TOOL_LOG.info("tool mdc checkpoint");
                    return ToolResult.ok("second");
                }));
        Conversation conversation = new Conversation(SessionId.of("parallel-session"));
        conversation.append(UserMessage.of("run tools"));

        try (LogCapture logs = LogCapture.forLogger("test.tool.mdc", Level.INFO)) {
            new AgentExecutor(stub, tools)
                    .run(conversation, new CancellationToken())
                    .join();

            assertThat(logs.events()).hasSize(2);
            assertThat(logs.events()).allSatisfy(event -> {
                assertThat(event.getMDCPropertyMap())
                        .containsEntry("session", "parallel-session")
                        .containsEntry("turn", "1");
            });
            assertThat(logs.events())
                    .extracting(event -> event.getMDCPropertyMap().get("toolUseId"))
                    .containsExactlyInAnyOrder("u1", "u2");
        }
    }
}
