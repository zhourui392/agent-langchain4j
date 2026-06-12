package com.anthropic.cclc.interfaces.engine;

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
        assertThatThrownBy(() -> Class.forName("com.anthropic.cclc.interfaces.cli.CclcApplication"))
                .isInstanceOf(ClassNotFoundException.class);
    }
}
