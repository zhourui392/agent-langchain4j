package com.anthropic.agentkit.interfaces.engine;

import com.anthropic.agentkit.domain.diagnosis.DataSourceType;
import com.anthropic.agentkit.domain.diagnosis.DataSourceView;
import com.anthropic.agentkit.domain.diagnosis.EnvironmentContext;
import com.anthropic.agentkit.domain.diagnosis.OperationalContext;
import com.anthropic.agentkit.domain.diagnosis.ReadinessStatus;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Set;

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

    @Test
    void carriesTypedOperationalContext() {
        OperationalContext context = new OperationalContext(
                Instant.parse("2026-07-30T02:00:00Z"), ZoneId.of("UTC"),
                new EnvironmentContext("test", "local", "unknown"), "agent-web",
                List.of(new DataSourceView("local-logs", DataSourceType.LOG,
                        ReadinessStatus.READY, Set.of("query"))),
                Map.of("namespace", "agent-web-test"));

        RunRequest request = RunRequest.builder()
                .workingDir(".")
                .userMessage("inspect errors")
                .sessionId("s-context")
                .operationalContext(context)
                .build();

        assertThat(request.operationalContext()).isEqualTo(context);
        assertThat(request.env()).isEqualTo("test");
    }

    @Test
    void legacyEnvCreatesTypedEnvironmentFallback() {
        RunRequest request = RunRequest.builder()
                .workingDir(".")
                .userMessage("inspect errors")
                .sessionId("s-legacy-env")
                .env("prod")
                .build();

        assertThat(request.operationalContext().environment().name()).isEqualTo("prod");
        assertThat(request.operationalContext().hasKnownEnvironment()).isTrue();
    }
}
