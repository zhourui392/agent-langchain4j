package com.anthropic.cclc.infrastructure.memory;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class SessionPathsTest {

    @Test
    void storesBaseDirectoryAndBuildsDefaultLocationFromUserHome() {
        SessionPaths explicit = new SessionPaths(Path.of("D:\\sessions"));
        String oldHome = System.getProperty("user.home");
        try {
            System.setProperty("user.home", "D:\\home");

            SessionPaths defaults = SessionPaths.defaultLocation();

            assertThat(explicit.baseDirectory()).isEqualTo(Path.of("D:\\sessions"));
            assertThat(defaults.baseDirectory().toString()).endsWith(".claude-code-j\\sessions");
        } finally {
            System.setProperty("user.home", oldHome);
        }
    }
}
