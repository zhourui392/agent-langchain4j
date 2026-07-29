package com.anthropic.agentkit.interfaces.cli;

import com.anthropic.agentkit.application.task.BackgroundTaskService;
import com.anthropic.agentkit.domain.agent.AgentId;
import com.anthropic.agentkit.domain.context.ContextProvider;
import com.anthropic.agentkit.domain.tool.ToolRegistry;
import com.anthropic.agentkit.infrastructure.task.FileArtifactStore;
import com.anthropic.agentkit.infrastructure.task.ProcessBackgroundTaskLauncher;
import com.anthropic.agentkit.infrastructure.tools.support.FileStateCache;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class AgentKitApplicationTest {

    @Test
    void mainPrintsVersionAndHelpWithoutStartingRepl() {
        String version = captureStdout(() -> AgentKitApplication.main(new String[]{"--version"}));
        String help = captureStdout(() -> AgentKitApplication.main(new String[]{"--help"}));

        assertThat(version).contains("agentkit", AgentKitApplication.version());
        assertThat(help).contains(
                "Usage:", "--agent", "AK_API_KEY", "OPENAI_API_KEY", "AK_SKILLS_DIR");
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
    void backgroundRuntimeToolsAreRegisteredByCompositionRoot(@TempDir Path tempDir)
            throws Exception {
        try (ProcessBackgroundTaskLauncher launcher = new ProcessBackgroundTaskLauncher();
             BackgroundTaskService tasks = new BackgroundTaskService(
                     launcher, new FileArtifactStore(
                             tempDir, 1_024, Duration.ofMinutes(5), Clock.systemUTC()))) {
            ToolRegistry registry = (ToolRegistry) invoke("registerTools",
                    new Class<?>[]{FileStateCache.class, Optional.class,
                            BackgroundTaskService.class},
                    new FileStateCache(), Optional.empty(), tasks);

            assertThat(registry.names()).containsExactly(
                    "Bash", "Read", "Write", "Edit", "Glob", "Grep",
                    "BashBackground", "TaskStatus", "TaskRead", "TaskStop");
        }
    }

    @Test
    void historyFileUsesUserHome(@TempDir Path tempDir) throws Exception {
        String oldHome = System.getProperty("user.home");
        try {
            System.setProperty("user.home", tempDir.toString());

            Path history = (Path) invoke("historyFile", new Class<?>[]{});

            assertThat(history).isEqualTo(tempDir.resolve(".agentkit").resolve("history"));
        } finally {
            System.setProperty("user.home", oldHome);
        }
    }

    @Test
    void agentSelectionDefaultsAndSupportsBothOptionForms() throws Exception {
        AgentId defaultId = (AgentId) invoke(
                "selectedAgentId", new Class<?>[]{String[].class}, (Object) new String[]{});
        AgentId separate = (AgentId) invoke(
                "selectedAgentId", new Class<?>[]{String[].class},
                (Object) new String[]{"--agent", "diagnosis"});
        AgentId joined = (AgentId) invoke(
                "selectedAgentId", new Class<?>[]{String[].class},
                (Object) new String[]{"--agent=coding"});

        assertThat(defaultId).isEqualTo(CliAgentManifest.ID);
        assertThat(separate).isEqualTo(AgentId.of("diagnosis"));
        assertThat(joined).isEqualTo(AgentId.of("coding"));
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

}
