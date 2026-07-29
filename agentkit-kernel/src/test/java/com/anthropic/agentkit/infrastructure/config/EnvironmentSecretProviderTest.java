package com.anthropic.agentkit.infrastructure.config;

import ch.qos.logback.classic.Level;
import com.anthropic.agentkit.domain.agent.RunId;
import com.anthropic.agentkit.domain.agent.WorkspaceId;
import com.anthropic.agentkit.domain.port.SecretScope;
import com.anthropic.agentkit.testsupport.LogCapture;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class EnvironmentSecretProviderTest {

    @Test
    void auditContainsScopeAndLookupOutcomeButNotSecretValue() {
        String secretValue = "never-log-this-value";
        EnvironmentSecretProvider provider = new EnvironmentSecretProvider(
                Map.of("SERVICE_TOKEN", secretValue)::get);
        SecretScope scope = new SecretScope(
                RunId.of("audit-run"), WorkspaceId.of("audit-workspace"));

        try (LogCapture logs = LogCapture.forClass(EnvironmentSecretProvider.class, Level.INFO)) {
            assertThat(provider.find(scope, "SERVICE_TOKEN")).contains(secretValue);

            assertThat(logs.events()).singleElement().satisfies(event -> {
                assertThat(event.getFormattedMessage())
                        .contains("audit-run", "audit-workspace", "SERVICE_TOKEN", "found=true")
                        .doesNotContain(secretValue);
            });
        }
    }
}
