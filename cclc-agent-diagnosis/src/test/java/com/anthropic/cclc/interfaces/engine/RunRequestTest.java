package com.anthropic.cclc.interfaces.engine;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RunRequestTest {

    @Test
    void carriesStateSnapshotSeparatelyFromHistory() {
        RunRequest request = RunRequest.builder()
                .workingDir(".")
                .userMessage("hi")
                .sessionId("s-1")
                .stateSnapshot("{\"schemaVersion\":1}")
                .build();

        assertThat(request.stateSnapshot()).isEqualTo("{\"schemaVersion\":1}");
        assertThat(request.history()).isEmpty();
    }
}
