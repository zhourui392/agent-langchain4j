package com.anthropic.agentkit.infrastructure.memory;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class SessionPathsTest {

    @TempDir
    Path home;

    @Test
    void storesBaseDirectoryAndBuildsDefaultLocationFromUserHome() {
        SessionPaths explicit = new SessionPaths(Path.of("D:\\sessions"));
        String oldHome = System.getProperty("user.home");
        try {
            System.setProperty("user.home", home.toString());

            SessionPaths defaults = SessionPaths.defaultLocation();

            assertThat(explicit.baseDirectory()).isEqualTo(Path.of("D:\\sessions"));
            assertThat(defaults.baseDirectory()).isEqualTo(home.resolve(".agentkit").resolve("sessions"));
        } finally {
            System.setProperty("user.home", oldHome);
        }
    }
}
