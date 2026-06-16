package com.anthropic.agentkit.interfaces.cli;

import com.anthropic.agentkit.application.AgentExecutor;
import com.anthropic.agentkit.application.SessionResumer;
import com.anthropic.agentkit.application.SystemPromptComposer;
import com.anthropic.agentkit.application.io.TerminalIo;
import com.anthropic.agentkit.domain.conversation.CancellationToken;
import com.anthropic.agentkit.domain.context.ContextProvider;
import com.anthropic.agentkit.domain.conversation.SessionId;
import com.anthropic.agentkit.domain.message.ChatMessage;
import com.anthropic.agentkit.domain.port.ChatMemoryStore;
import com.anthropic.agentkit.domain.port.LlmClient;
import com.anthropic.agentkit.domain.tool.ToolRegistry;
import com.anthropic.agentkit.infrastructure.memory.FileChatMemoryStore;
import com.anthropic.agentkit.infrastructure.tools.support.FileStateCache;
import com.anthropic.agentkit.testsupport.io.ScriptedTerminalIo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class AgentKitApplicationTest {

    @Test
    void mainPrintsVersionAndHelpWithoutStartingRepl() {
        String version = captureStdout(() -> AgentKitApplication.main(new String[]{"--version"}));
        String help = captureStdout(() -> AgentKitApplication.main(new String[]{"--help"}));

        assertThat(version).contains("agentkit", AgentKitApplication.version());
        assertThat(help).contains("Usage:", "AK_API_KEY", "OPENAI_API_KEY", "AK_SKILLS_DIR");
    }

    @Test
    void privateHelpersExposeExpectedCliDefaults() throws Exception {
        assertThat((Boolean) invoke("containsFlag",
                new Class<?>[]{String[].class, String[].class},
                new String[]{"-v"}, new String[]{"--version", "-v"})).isTrue();
        assertThat((Boolean) invoke("containsFlag",
                new Class<?>[]{String[].class, String[].class},
                new String[]{"--other"}, new String[]{"--version", "-v"})).isFalse();

        ToolRegistry registry = (ToolRegistry) invoke("registerTools",
                new Class<?>[]{FileStateCache.class}, new FileStateCache());
        assertThat(registry.names()).containsExactly("Bash", "Read", "Write", "Edit", "Glob", "Grep");

        @SuppressWarnings("unchecked")
        List<ContextProvider> providers = (List<ContextProvider>) invoke("contextProviders", new Class<?>[]{});
        assertThat(providers).extracting(ContextProvider::key)
                .containsExactly("agents_md", "cwd", "date", "git_status");
    }

    @Test
    void skillDirectoryEnvironmentAddsSkillToolAndContextProvider(@TempDir Path tempDir) throws Exception {
        Path skillsRoot = tempDir.resolve("skills");
        writeSkill(skillsRoot.resolve("es-slow-query"), """
                ---
                description: Diagnose slow ES queries.
                ---
                # ES
                """);
        try {
            System.setProperty("AK_SKILLS_DIR", skillsRoot.toString());

            ToolRegistry registry = (ToolRegistry) invoke("registerTools",
                    new Class<?>[]{FileStateCache.class}, new FileStateCache());
            @SuppressWarnings("unchecked")
            List<ContextProvider> providers = (List<ContextProvider>) invoke("contextProviders", new Class<?>[]{});

            assertThat(registry.names()).contains("Skill");
            assertThat(providers).extracting(ContextProvider::key).contains("skills");
        } finally {
            System.clearProperty("AK_SKILLS_DIR");
        }
    }

    @Test
    void historyFileUsesUserHome() throws Exception {
        String oldHome = System.getProperty("user.home");
        try {
            System.setProperty("user.home", "D:\\tmp\\home");

            Path history = (Path) invoke("historyFile", new Class<?>[]{});

            assertThat(history.toString()).endsWith(".agentkit\\history");
        } finally {
            System.setProperty("user.home", oldHome);
        }
    }

    @Test
    void handleLineExecutesSlashCommandBranches() throws Exception {
        ScriptedTerminalIo terminal = ScriptedTerminalIo.builder().build();
        SlashCommandParser parser = new SlashCommandParser()
                .register(new HelpCommand())
                .register(new ClearCommand());

        handleLine("/missing", parser, terminal, null);
        handleLine("/help", parser, terminal, null);

        assertThat(terminal.errorOutput()).contains("unknown command");
        assertThat(terminal.output()).contains("/help", "/clear");
    }

    @Test
    void handleLineResumesExistingSession() throws Exception {
        ScriptedTerminalIo terminal = ScriptedTerminalIo.builder().build();
        SlashCommandParser parser = new SlashCommandParser().register(new ResumeCommand());
        SessionResumer resumer = new SessionResumer(new StubMemoryStore());

        handleLine("/resume session-1", parser, terminal, resumer);

        assertThat(terminal.output()).contains("(resumed session-1)");
    }

    private static void handleLine(String input, SlashCommandParser parser,
                                   TerminalIo terminal, SessionResumer resumer) throws Exception {
        invoke("handleLine", new Class<?>[]{
                        String.class,
                        SlashCommandParser.class,
                        AtomicReference.class,
                        AgentExecutor.class,
                        LlmClient.class,
                        SystemPromptComposer.class,
                        Path.class,
                        CancellationToken.class,
                        FileChatMemoryStore.class,
                        SessionResumer.class,
                        OutputRenderer.class,
                        TerminalIo.class,
                        SigintHandler.class},
                input, parser, new AtomicReference<>(), null, null, null, Path.of("."),
                new CancellationToken(), null, resumer, null, terminal, null);
    }

    private static Object invoke(String name, Class<?>[] parameterTypes, Object... args) throws Exception {
        Method method = AgentKitApplication.class.getDeclaredMethod(name, parameterTypes);
        method.setAccessible(true);
        return method.invoke(null, args);
    }

    private static String captureStdout(Runnable runnable) {
        PrintStream oldOut = System.out;
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(bytes));
            runnable.run();
            return bytes.toString();
        } finally {
            System.setOut(oldOut);
        }
    }

    private static void writeSkill(Path directory, String content) throws IOException {
        Files.createDirectories(directory);
        Files.writeString(directory.resolve("SKILL.md"), content);
    }

    private static final class ResumeCommand implements SlashCommand {

        @Override
        public String name() {
            return "resume";
        }

        @Override
        public String execute(List<String> args) {
            return "unused";
        }
    }

    private static final class StubMemoryStore implements ChatMemoryStore {

        @Override
        public List<ChatMessage> load(SessionId sessionId) {
            return List.of(com.anthropic.agentkit.domain.message.UserMessage.of("old"));
        }

        @Override
        public void save(SessionId sessionId, List<ChatMessage> messages) {
        }

        @Override
        public void delete(SessionId sessionId) {
        }
    }
}
