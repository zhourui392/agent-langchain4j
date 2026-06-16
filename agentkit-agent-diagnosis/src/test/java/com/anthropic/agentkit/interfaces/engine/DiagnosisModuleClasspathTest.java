package com.anthropic.agentkit.interfaces.engine;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DiagnosisModuleClasspathTest {

    @Test
    void diagnosisArtifactDoesNotBringCliJlineDependency() {
        assertThatThrownBy(() -> Class.forName("org.jline.reader.LineReader"))
                .isInstanceOf(ClassNotFoundException.class);
    }

    @Test
    void diagnosisArtifactDoesNotBringCliClasses() {
        assertThatThrownBy(() -> Class.forName("com.anthropic.agentkit.interfaces.cli.AgentKitApplication"))
                .isInstanceOf(ClassNotFoundException.class);
    }
}
