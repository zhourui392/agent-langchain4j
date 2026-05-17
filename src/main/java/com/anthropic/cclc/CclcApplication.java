package com.anthropic.cclc;

import com.anthropic.cclc.application.AgentExecutor;
import com.anthropic.cclc.application.PermissionService;
import com.anthropic.cclc.application.SessionResumer;
import com.anthropic.cclc.application.SystemPromptComposer;
import com.anthropic.cclc.application.TerminalIoPrompter;
import com.anthropic.cclc.application.io.TerminalIo;
import com.anthropic.cclc.domain.context.ContextProvider;
import com.anthropic.cclc.domain.conversation.CancellationToken;
import com.anthropic.cclc.domain.conversation.Conversation;
import com.anthropic.cclc.domain.conversation.SessionId;
import com.anthropic.cclc.domain.message.AiMessage;
import com.anthropic.cclc.domain.message.UserMessage;
import com.anthropic.cclc.domain.permission.PermissionMode;
import com.anthropic.cclc.domain.port.LlmClient;
import com.anthropic.cclc.domain.tool.ExecutionContext;
import com.anthropic.cclc.domain.tool.Tool;
import com.anthropic.cclc.domain.tool.ToolRegistry;
import com.anthropic.cclc.infrastructure.config.AppConfig;
import com.anthropic.cclc.infrastructure.config.ConfigLoader;
import com.anthropic.cclc.infrastructure.context.ClaudeMdProvider;
import com.anthropic.cclc.infrastructure.context.CwdProvider;
import com.anthropic.cclc.infrastructure.context.DateProvider;
import com.anthropic.cclc.infrastructure.context.GitStatusProvider;
import com.anthropic.cclc.infrastructure.llm.AnthropicLlmClientFactory;
import com.anthropic.cclc.infrastructure.memory.FileChatMemoryStore;
import com.anthropic.cclc.infrastructure.memory.SessionPaths;
import com.anthropic.cclc.infrastructure.permission.DefaultPermissionPolicy;
import com.anthropic.cclc.infrastructure.tools.BashTool;
import com.anthropic.cclc.infrastructure.tools.FileEditTool;
import com.anthropic.cclc.infrastructure.tools.FileReadTool;
import com.anthropic.cclc.infrastructure.tools.FileWriteTool;
import com.anthropic.cclc.infrastructure.tools.GlobTool;
import com.anthropic.cclc.infrastructure.tools.GrepTool;
import com.anthropic.cclc.infrastructure.tools.support.FileStateCache;
import com.anthropic.cclc.interfaces.cli.ClearCommand;
import com.anthropic.cclc.interfaces.cli.HelpCommand;
import com.anthropic.cclc.interfaces.cli.OutputRenderer;
import com.anthropic.cclc.interfaces.cli.ReplLoop;
import com.anthropic.cclc.interfaces.cli.SigintHandler;
import com.anthropic.cclc.interfaces.cli.SlashCommandParser;
import com.anthropic.cclc.interfaces.cli.io.JLineTerminalIo;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

public final class CclcApplication {

    private static final String SYSTEM_INSTRUCTIONS = """
            You are Claude Code, a CLI coding assistant. Be concise. \
            Use available tools to read, search, and modify files when asked. \
            Always summarize your actions briefly.""";

    private CclcApplication() {
    }

    public static void main(String[] args) {
        if (containsFlag(args, "--version", "-v")) {
            System.out.println("claude-code-langchain4j " + version());
            return;
        }
        if (containsFlag(args, "--help", "-h")) {
            printUsage();
            return;
        }
        try {
            new CclcApplication().run();
        } catch (IllegalStateException missingConfig) {
            System.err.println(missingConfig.getMessage());
            System.exit(1);
        } catch (IOException io) {
            System.err.println("io error: " + io.getMessage());
            System.exit(1);
        }
    }

    private void run() throws IOException {
        AppConfig config = ConfigLoader.fromSystem().load();
        LlmClient llm = AnthropicLlmClientFactory.withCacheEnabled().create(config);

        Path cwd = Paths.get(System.getProperty("user.dir", "."));
        FileStateCache fileStateCache = new FileStateCache();
        ToolRegistry tools = registerTools(fileStateCache);

        SystemPromptComposer composer = new SystemPromptComposer(SYSTEM_INSTRUCTIONS, contextProviders());
        FileChatMemoryStore store = new FileChatMemoryStore(SessionPaths.defaultLocation().baseDirectory());
        SessionResumer resumer = new SessionResumer(store);

        CancellationToken cancel = new CancellationToken();
        SigintHandler sigint = new SigintHandler(cancel, () -> System.exit(130));

        try (JLineTerminalIo terminalIo = JLineTerminalIo.openSystem(historyFile())) {
            PermissionService permissions = new PermissionService(
                    new DefaultPermissionPolicy(),
                    new TerminalIoPrompter(terminalIo),
                    PermissionMode.DEFAULT);
            AgentExecutor executor = new AgentExecutor(llm, tools, permissions, ExecutionContext.of(cwd, cancel));

            OutputRenderer renderer = new OutputRenderer(terminalIo);
            AtomicReference<Conversation> active = new AtomicReference<>(new Conversation(SessionId.fresh()));
            SlashCommandParser parser = new SlashCommandParser()
                    .register(new HelpCommand())
                    .register(new ClearCommand());

            ReplLoop repl = new ReplLoop(terminalIo, input -> handleLine(
                    input, parser, active, executor, llm, composer, cwd, cancel,
                    store, resumer, renderer, terminalIo, sigint));
            repl.run();
        }
    }

    private static ToolRegistry registerTools(FileStateCache fileStateCache) {
        return new ToolRegistry()
                .register(new BashTool())
                .register(new FileReadTool(fileStateCache))
                .register(new FileWriteTool(fileStateCache))
                .register(new FileEditTool(fileStateCache))
                .register(new GlobTool())
                .register(new GrepTool());
    }

    private static List<ContextProvider> contextProviders() {
        return List.of(
                new ClaudeMdProvider(),
                new CwdProvider(),
                new DateProvider(),
                new GitStatusProvider());
    }

    private static void handleLine(String input,
                                    SlashCommandParser parser,
                                    AtomicReference<Conversation> active,
                                    AgentExecutor executor,
                                    LlmClient llm,
                                    SystemPromptComposer composer,
                                    Path cwd,
                                    CancellationToken cancel,
                                    FileChatMemoryStore store,
                                    SessionResumer resumer,
                                    OutputRenderer renderer,
                                    TerminalIo terminalIo,
                                    SigintHandler sigint) {
        SlashCommandParser.ParseResult parsed = parser.parse(input);
        switch (parsed) {
            case SlashCommandParser.UnknownCommand unknown ->
                    terminalIo.writeError("unknown command: /" + unknown.name());
            case SlashCommandParser.CommandInvocation invocation -> {
                if ("resume".equals(invocation.command().name()) && !invocation.args().isEmpty()) {
                    Conversation resumed = resumer.resume(SessionId.of(invocation.args().get(0)));
                    active.set(resumed);
                    terminalIo.writeLine("(resumed " + resumed.sessionId() + ")");
                } else {
                    terminalIo.writeLine(invocation.command().execute(invocation.args()));
                }
            }
            case SlashCommandParser.UserMessage userText -> {
                Conversation conversation = active.get();
                conversation.append(UserMessage.of(userText.text()));
                runTurn(conversation, executor, composer, cwd, cancel, renderer);
                store.save(conversation.sessionId(), conversation.messages());
                sigint.turnFinished();
            }
        }
    }

    private static void runTurn(Conversation conversation,
                                 AgentExecutor executor,
                                 SystemPromptComposer composer,
                                 Path cwd,
                                 CancellationToken cancel,
                                 OutputRenderer renderer) {
        try {
            executor.run(conversation, cancel, renderer).join();
        } catch (Exception ex) {
            renderer.onError(ex);
        }
    }

    private static boolean containsFlag(String[] args, String... flags) {
        for (String arg : args) {
            for (String flag : flags) {
                if (flag.equals(arg)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static Path historyFile() {
        return Paths.get(System.getProperty("user.home", "."), ".claude-code-j", "history");
    }

    private static void printUsage() {
        System.out.println("""
                Usage: claude-code-langchain4j [options]
                  --version, -v   Print version
                  --help, -h      Print this help

                Required env: ANTHROPIC_API_KEY
                Optional env:  CCLC_MODEL, CCLC_MAX_TOKENS
                Config file:   ~/.claude-code-j/config.json (apiKey/model/maxTokens)""");
    }

    static String version() {
        Package pkg = CclcApplication.class.getPackage();
        String implementationVersion = pkg.getImplementationVersion();
        return implementationVersion != null ? implementationVersion : "0.1.0-SNAPSHOT";
    }
}
