package com.anthropic.agentkit.application;

import com.anthropic.agentkit.domain.agent.AgentBudget;
import com.anthropic.agentkit.domain.agent.AgentRunContext;
import com.anthropic.agentkit.domain.agent.AgentRunResult;
import com.anthropic.agentkit.domain.conversation.CancellationToken;
import com.anthropic.agentkit.domain.conversation.Conversation;
import com.anthropic.agentkit.domain.conversation.SessionId;
import com.anthropic.agentkit.domain.message.AiMessage;
import com.anthropic.agentkit.domain.message.ChatMessage;
import com.anthropic.agentkit.domain.message.ToolResultMessage;
import com.anthropic.agentkit.domain.message.UserMessage;
import com.anthropic.agentkit.domain.permission.Decision;
import com.anthropic.agentkit.domain.permission.PermissionMode;
import com.anthropic.agentkit.domain.tool.ToolRegistry;
import com.anthropic.agentkit.infrastructure.config.AppConfig;
import com.anthropic.agentkit.infrastructure.config.ConfigLoader;
import com.anthropic.agentkit.infrastructure.config.LlmProvider;
import com.anthropic.agentkit.infrastructure.llm.AnthropicLlmClientFactory;
import com.anthropic.agentkit.infrastructure.llm.LangChain4jLlmClient;
import com.anthropic.agentkit.infrastructure.tools.BashTool;
import com.anthropic.agentkit.infrastructure.tools.GlobTool;
import com.anthropic.agentkit.infrastructure.tools.FileReadTool;
import com.anthropic.agentkit.infrastructure.tools.support.FileStateCache;
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
        AppConfig config = anthropicConfig();
        LangChain4jLlmClient llm = AnthropicLlmClientFactory.withCacheEnabled().create(config);
        FileStateCache cache = new FileStateCache();
        ToolRegistry tools = new ToolRegistry()
                .register(new BashTool())
                .register(new GlobTool())
                .register(new FileReadTool(cache));

        Path cwd = Paths.get(System.getProperty("user.dir", "."));
        CancellationToken cancel = new CancellationToken();
        AgentExecutor executor = new AgentExecutor(llm, tools, bypassPermissions());

        Conversation conversation = new Conversation(SessionId.fresh());
        conversation.append(UserMessage.of(
                "List the markdown files in the current working directory. " +
                "Pick whatever tool you have. Then tell me how many you found."));

        AgentRunContext context = AgentRunContext.create(
                conversation.sessionId(), cwd, cancel, AgentBudget.unlimited());
        AgentRunResult result = executor.run(conversation, context).get(120, TimeUnit.SECONDS);

        List<ToolResultMessage> results = toolResults(conversation);
        assertThat(results)
                .as("model should pick at least one tool when not told which to use")
                .isNotEmpty();
        assertThat(result.finalMessage().text())
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

    private static AppConfig anthropicConfig() {
        return new AppConfig(
                System.getenv("ANTHROPIC_API_KEY"),
                anthropicModel(),
                ConfigLoader.DEFAULT_MAX_TOKENS,
                System.getenv("ANTHROPIC_BASE_URL"),
                PermissionMode.BYPASS,
                LlmProvider.ANTHROPIC);
    }

    private static String anthropicModel() {
        String configured = System.getenv("AK_MODEL");
        return configured == null || configured.isBlank()
                ? ConfigLoader.DEFAULT_ANTHROPIC_MODEL
                : configured;
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
