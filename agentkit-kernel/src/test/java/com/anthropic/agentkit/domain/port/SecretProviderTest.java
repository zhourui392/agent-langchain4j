package com.anthropic.agentkit.domain.port;

import com.anthropic.agentkit.domain.agent.AgentBudget;
import com.anthropic.agentkit.domain.agent.RunId;
import com.anthropic.agentkit.domain.agent.WorkspaceId;
import com.anthropic.agentkit.domain.conversation.CancellationToken;
import com.anthropic.agentkit.domain.tool.ExecutionContext;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class SecretProviderTest {

    @Test
    void toolObtainsSecretOnlyThroughScopedSecretProvider() {
        RunId runId = RunId.of("secret-run");
        WorkspaceId workspaceId = WorkspaceId.of("secret-workspace");
        AtomicReference<SecretScope> observedScope = new AtomicReference<>();
        AtomicReference<String> observedName = new AtomicReference<>();
        SecretProvider provider = (scope, name) -> {
            observedScope.set(scope);
            observedName.set(name);
            return Optional.of("sensitive-value");
        };
        ExecutionContext context = ExecutionContext.of(
                runId, workspaceId, Path.of("."), new CancellationToken(),
                AgentBudget.unlimited(), provider);

        Optional<String> secret = context.secret("SERVICE_TOKEN");

        assertThat(secret).contains("sensitive-value");
        assertThat(observedScope).hasValue(new SecretScope(runId, workspaceId));
        assertThat(observedName).hasValue("SERVICE_TOKEN");
    }
}
