package com.anthropic.cclc.application;

import com.anthropic.cclc.domain.conversation.CancellationToken;
import com.anthropic.cclc.domain.conversation.Conversation;
import com.anthropic.cclc.domain.conversation.SessionId;
import com.anthropic.cclc.domain.message.AiMessage;
import com.anthropic.cclc.domain.message.ChatMessage;
import com.anthropic.cclc.domain.message.ToolResultMessage;
import com.anthropic.cclc.domain.message.UserMessage;
import com.anthropic.cclc.domain.permission.Decision;
import com.anthropic.cclc.domain.permission.PermissionMode;
import com.anthropic.cclc.domain.tool.ExecutionContext;
import com.anthropic.cclc.domain.tool.ToolRegistry;
import com.anthropic.cclc.infrastructure.config.AppConfig;
import com.anthropic.cclc.infrastructure.config.ConfigLoader;
import com.anthropic.cclc.infrastructure.llm.AnthropicLlmClientFactory;
import com.anthropic.cclc.infrastructure.llm.LangChain4jLlmClient;
import com.anthropic.cclc.infrastructure.tools.BashTool;
import com.anthropic.cclc.infrastructure.tools.GlobTool;
import com.anthropic.cclc.infrastructure.tools.FileReadTool;
import com.anthropic.cclc.infrastructure.tools.support.FileStateCache;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfEnvironmentVariable(named = "ANTHROPIC_API_KEY", matches = ".+")
class AgentExecutorImplicitToolSmokeIT {

    @Test
    void modelSelectsToolWithoutBeingNamed() throws Exception {
        AppConfig config = ConfigLoader.fromSystem().load();
        LangChain4jLlmClient llm = AnthropicLlmClientFactory.withCacheEnabled().create(config);
        FileStateCache cache = new FileStateCache();
        ToolRegistry tools = new ToolRegistry()
                .register(new BashTool())
                .register(new GlobTool())
                .register(new FileReadTool(cache));

        Path cwd = Paths.get(System.getProperty("user.dir", "."));
        CancellationToken cancel = new CancellationToken();
        AgentExecutor executor = new AgentExecutor(llm, tools,
                bypassPermissions(), ExecutionContext.of(cwd, cancel));

        Conversation conversation = new Conversation(SessionId.fresh());
        conversation.append(UserMessage.of(
                "List the markdown files in the current working directory. " +
                "Pick whatever tool you have. Then tell me how many you found."));

        AiMessage finalMessage = executor.run(conversation, cancel).get(120, TimeUnit.SECONDS);

        List<ToolResultMessage> results = toolResults(conversation);
        assertThat(results)
                .as("model should pick at least one tool when not told which to use")
                .isNotEmpty();
        assertThat(finalMessage.text())
                .as("model produced a final answer after tool round-trip")
                .isNotBlank();
    }

    private static PermissionService bypassPermissions() {
        return new PermissionService(
                (invocation, tool, mode) -> Decision.ALLOW,
                (invocation, tool) -> {
                    throw new IllegalStateException("interactive prompter must not run in smoke IT");
                },
                PermissionMode.BYPASS);
    }

    private static List<ToolResultMessage> toolResults(Conversation conversation) {
        List<ToolResultMessage> out = new ArrayList<>();
        for (ChatMessage m : conversation.messages()) {
            if (m instanceof ToolResultMessage tr) {
                out.add(tr);
            }
        }
        return out;
    }
}
