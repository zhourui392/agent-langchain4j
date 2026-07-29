package com.anthropic.agentkit.interfaces.cli;

import com.anthropic.agentkit.application.AgentExecutor;
import com.anthropic.agentkit.application.PermissionService;
import com.anthropic.agentkit.application.SessionResumer;
import com.anthropic.agentkit.application.SystemPromptComposer;
import com.anthropic.agentkit.application.TerminalIoPrompter;
import com.anthropic.agentkit.application.context.ContextPolicy;
import com.anthropic.agentkit.application.interception.AgentInterceptors;
import com.anthropic.agentkit.application.io.TerminalIo;
import com.anthropic.agentkit.application.task.ArtifactContentPolicy;
import com.anthropic.agentkit.application.task.BackgroundTaskCleanupInterceptor;
import com.anthropic.agentkit.application.task.BackgroundTaskService;
import com.anthropic.agentkit.application.tool.ArtifactToolOutputPolicy;
import com.anthropic.agentkit.application.tool.LimitedToolOutputPolicy;
import com.anthropic.agentkit.domain.agent.AgentBudget;
import com.anthropic.agentkit.domain.agent.AgentRunContext;
import com.anthropic.agentkit.domain.agent.AgentRunResult;
import com.anthropic.agentkit.domain.context.ContextProvider;
import com.anthropic.agentkit.domain.conversation.CancellationToken;
import com.anthropic.agentkit.domain.conversation.Conversation;
import com.anthropic.agentkit.domain.conversation.SessionId;
import com.anthropic.agentkit.domain.message.UserMessage;
import com.anthropic.agentkit.domain.port.LlmClient;
import com.anthropic.agentkit.domain.port.SecretProvider;
import com.anthropic.agentkit.domain.skill.SkillCatalog;
import com.anthropic.agentkit.domain.tool.ToolRegistry;
import com.anthropic.agentkit.infrastructure.config.AppConfig;
import com.anthropic.agentkit.infrastructure.config.ConfigLoader;
import com.anthropic.agentkit.infrastructure.config.EnvironmentSecretProvider;
import com.anthropic.agentkit.infrastructure.context.AgentsMdProvider;
import com.anthropic.agentkit.infrastructure.context.CwdProvider;
import com.anthropic.agentkit.infrastructure.context.DateProvider;
import com.anthropic.agentkit.infrastructure.context.GitStatusProvider;
import com.anthropic.agentkit.infrastructure.llm.LlmClientFactories;
import com.anthropic.agentkit.infrastructure.memory.FileChatMemoryStore;
import com.anthropic.agentkit.infrastructure.memory.FileRunEventStore;
import com.anthropic.agentkit.infrastructure.memory.FileRunSuspensionStore;
import com.anthropic.agentkit.infrastructure.memory.SessionPaths;
import com.anthropic.agentkit.infrastructure.permission.DefaultPermissionPolicy;
import com.anthropic.agentkit.infrastructure.skill.DirectorySkillSource;
import com.anthropic.agentkit.infrastructure.skill.SkillCatalogContextProvider;
import com.anthropic.agentkit.infrastructure.skill.SkillFrontmatterParser;
import com.anthropic.agentkit.infrastructure.task.FileArtifactStore;
import com.anthropic.agentkit.infrastructure.task.ProcessBackgroundTaskLauncher;
import com.anthropic.agentkit.infrastructure.tools.BashTool;
import com.anthropic.agentkit.infrastructure.tools.BackgroundBashTool;
import com.anthropic.agentkit.infrastructure.tools.FileEditTool;
import com.anthropic.agentkit.infrastructure.tools.FileReadTool;
import com.anthropic.agentkit.infrastructure.tools.FileWriteTool;
import com.anthropic.agentkit.infrastructure.tools.GlobTool;
import com.anthropic.agentkit.infrastructure.tools.GrepTool;
import com.anthropic.agentkit.infrastructure.tools.SkillTool;
import com.anthropic.agentkit.infrastructure.tools.TaskReadTool;
import com.anthropic.agentkit.infrastructure.tools.TaskStatusTool;
import com.anthropic.agentkit.infrastructure.tools.TaskStopTool;
import com.anthropic.agentkit.infrastructure.tools.support.FileStateCache;
import com.anthropic.agentkit.interfaces.cli.io.JLineTerminalIo;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class AgentKitApplication {

    private static final Logger log = LoggerFactory.getLogger(AgentKitApplication.class);

    private static final String SYSTEM_INSTRUCTIONS = """
            You are AgentKit, a CLI coding assistant. Be concise. \
            Use available tools to read, search, and modify files when asked. \
            Always summarize your actions briefly.""";
    private static final String SKILLS_DIR_ENV = "AK_SKILLS_DIR";
    private static final int ARTIFACT_MAX_CHARACTERS = 1_000_000;
    private static final Duration ARTIFACT_TTL = Duration.ofHours(1);

    private AgentKitApplication() {
    }

    public static void main(String[] args) {
        if (containsFlag(args, "--version", "-v")) {
            System.out.println("agentkit " + version());
            return;
        }
        if (containsFlag(args, "--help", "-h")) {
            printUsage();
            return;
        }
        try {
            new AgentKitApplication().run();
        } catch (IllegalStateException missingConfig) {
            log.error("configuration load failed", missingConfig);
            System.err.println(missingConfig.getMessage());
            System.exit(1);
        } catch (IOException io) {
            log.error("fatal io error during startup", io);
            System.err.println("io error: " + io.getMessage());
            System.exit(1);
        } catch (RuntimeException fatal) {
            log.error("fatal startup error", fatal);
            System.err.println("fatal error: " + fatal.getMessage());
            System.exit(1);
        }
    }

    private void run() throws IOException {
        AppConfig config = ConfigLoader.fromSystem().load();
        LlmClient llm = LlmClientFactories.create(config);
        SecretProvider secrets = EnvironmentSecretProvider.system();

        Path cwd = Paths.get(System.getProperty("user.dir", "."));
        FileStateCache fileStateCache = new FileStateCache();
        java.util.Optional<SkillCatalog> skills = loadSkills();

        SystemPromptComposer composer = new SystemPromptComposer(SYSTEM_INSTRUCTIONS, contextProviders(skills));
        FileChatMemoryStore store = new FileChatMemoryStore(SessionPaths.defaultLocation().baseDirectory());
        FileRunEventStore eventStore = new FileRunEventStore(
                SessionPaths.defaultLocation().baseDirectory().resolve("runs"));
        FileRunSuspensionStore suspensionStore = new FileRunSuspensionStore(
                SessionPaths.defaultLocation().baseDirectory().resolve("suspensions"));
        SessionResumer resumer = new SessionResumer(store);

        CancellationToken cancel = new CancellationToken();
        SigintHandler sigint = new SigintHandler(cancel, () -> System.exit(130));

        try (BackgroundTaskRuntime background = openBackgroundRuntime();
             JLineTerminalIo terminalIo = JLineTerminalIo.openSystem(historyFile())) {
            ToolRegistry tools = registerTools(fileStateCache, skills, background.tasks());
            log.info("agentkit starting: provider={}, model={}, permissionMode={}, skillsEnabled={}, registeredTools={}",
                    config.provider(), config.model(), config.permissionMode(),
                    skills.isPresent(), tools.names().size());
            PermissionService permissions = new PermissionService(
                    new DefaultPermissionPolicy(),
                    new TerminalIoPrompter(terminalIo),
                    config.permissionMode());
            AgentExecutor executor = createExecutor(
                    llm, tools, permissions, eventStore, suspensionStore, background);

            OutputRenderer renderer = new OutputRenderer(terminalIo);
            terminalIo.writeLine("(permission mode: " + config.permissionMode() + ")");
            AtomicReference<Conversation> active = new AtomicReference<>(new Conversation(SessionId.fresh()));
            SlashCommandParser parser = new SlashCommandParser()
                    .register(new HelpCommand())
                    .register(new ClearCommand());

            ReplLoop repl = new ReplLoop(terminalIo, input -> handleLine(
                    input, parser, active, executor, llm, composer, cwd, cancel,
                    store, resumer, renderer, terminalIo, sigint, secrets));
            repl.run();
        }
    }

    private static ToolRegistry registerTools(FileStateCache fileStateCache) {
        return registerTools(fileStateCache, loadSkills());
    }

    private static ToolRegistry registerTools(FileStateCache fileStateCache,
                                              java.util.Optional<SkillCatalog> skills) {
        ToolRegistry registry = new ToolRegistry()
                .register(new BashTool())
                .register(new FileReadTool(fileStateCache))
                .register(new FileWriteTool(fileStateCache))
                .register(new FileEditTool(fileStateCache))
                .register(new GlobTool())
                .register(new GrepTool());
        skills.ifPresent(catalog -> registry.register(new SkillTool(catalog)));
        return registry;
    }

    private static ToolRegistry registerTools(
            FileStateCache fileStateCache,
            java.util.Optional<SkillCatalog> skills,
            BackgroundTaskService backgroundTasks) {
        return registerTools(fileStateCache, skills)
                .register(new BackgroundBashTool(backgroundTasks))
                .register(new TaskStatusTool(backgroundTasks))
                .register(new TaskReadTool(backgroundTasks))
                .register(new TaskStopTool(backgroundTasks));
    }

    private static BackgroundTaskRuntime openBackgroundRuntime() {
        Path root = SessionPaths.defaultLocation().baseDirectory().resolve("artifacts");
        ProcessBackgroundTaskLauncher launcher = new ProcessBackgroundTaskLauncher();
        FileArtifactStore artifacts = new FileArtifactStore(
                root, ARTIFACT_MAX_CHARACTERS, ARTIFACT_TTL, Clock.systemUTC());
        return new BackgroundTaskRuntime(
                launcher, new BackgroundTaskService(launcher, artifacts), artifacts);
    }

    private static AgentExecutor createExecutor(
            LlmClient llm,
            ToolRegistry tools,
            PermissionService permissions,
            FileRunEventStore eventStore,
            FileRunSuspensionStore suspensionStore,
            BackgroundTaskRuntime background) {
        return new AgentExecutor(
                llm, tools, permissions, ContextPolicy.standard(llm),
                ArtifactToolOutputPolicy.of(
                        LimitedToolOutputPolicy.DEFAULT_MAX_CHARACTERS,
                        background.artifacts(), ArtifactContentPolicy.redactInlineSecrets()),
                eventStore,
                AgentInterceptors.ordered(
                        new BackgroundTaskCleanupInterceptor(background.tasks())),
                suspensionStore);
    }

    private static List<ContextProvider> contextProviders() {
        return contextProviders(loadSkills());
    }

    private static List<ContextProvider> contextProviders(java.util.Optional<SkillCatalog> skills) {
        List<ContextProvider> providers = new java.util.ArrayList<>(List.of(
                new AgentsMdProvider(),
                new CwdProvider(),
                new DateProvider(),
                new GitStatusProvider()));
        skills.ifPresent(catalog -> providers.add(new SkillCatalogContextProvider(catalog)));
        return List.copyOf(providers);
    }

    private static java.util.Optional<SkillCatalog> loadSkills() {
        String directory = skillDirectorySetting();
        if (directory == null || directory.isBlank()) {
            log.debug("skills directory not configured");
            return java.util.Optional.empty();
        }
        SkillCatalog catalog = SkillCatalog.of(new DirectorySkillSource(
                Paths.get(directory), new SkillFrontmatterParser()).load());
        log.info("skills loaded: directory={}, count={}", directory, catalog.names().size());
        return catalog.isEmpty() ? java.util.Optional.empty() : java.util.Optional.of(catalog);
    }

    private static String skillDirectorySetting() {
        String configured = System.getenv(SKILLS_DIR_ENV);
        if (configured == null || configured.isBlank()) {
            configured = System.getProperty(SKILLS_DIR_ENV);
        }
        return configured;
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
                                    SigintHandler sigint,
                                    SecretProvider secrets) {
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
                runTurn(conversation, executor, composer, cwd, cancel,
                        renderer, terminalIo, secrets);
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
                                 OutputRenderer renderer,
                                 TerminalIo terminalIo,
                                 SecretProvider secrets) {
        try {
            String systemPrompt = composer.compose(cwd).full();
            AgentRunContext context = AgentRunContext.create(
                    conversation.sessionId(), cwd, cancel, AgentBudget.unlimited(), secrets);
            AgentRunResult result = executor.run(
                    conversation, context, renderer, systemPrompt).join();
            RunSuspensionPrompter prompter = new RunSuspensionPrompter(terminalIo);
            while (result.suspension().isPresent()) {
                var command = prompter.prompt(result);
                if (command.isEmpty()) {
                    break;
                }
                AgentRunContext resumed = AgentRunContext.create(
                        conversation.sessionId(), cwd, cancel,
                        AgentBudget.unlimited(), secrets);
                result = executor.resume(
                        conversation, resumed, command.orElseThrow(),
                        renderer, systemPrompt).join();
            }
        } catch (RuntimeException ex) {
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
        return Paths.get(System.getProperty("user.home", "."), ".agentkit", "history");
    }

    private static void printUsage() {
        System.out.println("""
                Usage: agentkit [options]
                  --version, -v   Print version
                  --help, -h      Print this help

                Required env: AK_API_KEY, OPENAI_API_KEY, or ANTHROPIC_API_KEY
                Optional env: AK_PROVIDER, AK_MODEL, AK_MAX_TOKENS, AK_BASE_URL, AK_SKILLS_DIR
                Config file:  ~/.agentkit/config.json (provider/apiKey/model/maxTokens/baseUrl)""");
    }

    static String version() {
        Package pkg = AgentKitApplication.class.getPackage();
        String implementationVersion = pkg.getImplementationVersion();
        return implementationVersion != null ? implementationVersion : "0.2.0";
    }

    private record BackgroundTaskRuntime(
            ProcessBackgroundTaskLauncher launcher,
            BackgroundTaskService tasks,
            FileArtifactStore artifacts) implements AutoCloseable {

        @Override
        public void close() {
            tasks.close();
            launcher.close();
        }
    }
}
