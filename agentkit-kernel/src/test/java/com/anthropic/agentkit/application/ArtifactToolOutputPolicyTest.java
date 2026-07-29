package com.anthropic.agentkit.application;

import com.anthropic.agentkit.application.task.ArtifactContentPolicy;
import com.anthropic.agentkit.application.tool.ArtifactToolOutputPolicy;
import com.anthropic.agentkit.domain.port.ArtifactStore;
import com.anthropic.agentkit.domain.task.ArtifactId;
import com.anthropic.agentkit.domain.task.ArtifactReference;
import com.anthropic.agentkit.domain.task.TaskScope;
import com.anthropic.agentkit.domain.tool.ExecutionContext;
import com.anthropic.agentkit.domain.tool.ToolArguments;
import com.anthropic.agentkit.domain.tool.ToolInvocation;
import com.anthropic.agentkit.domain.tool.ToolOutputMetadata;
import com.anthropic.agentkit.domain.tool.ToolResult;
import com.anthropic.agentkit.domain.tool.ToolUseId;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class ArtifactToolOutputPolicyTest {

    @Test
    void largeToolOutputPersistsGovernedContentAndReturnsReference() {
        MemoryArtifactStore artifacts = new MemoryArtifactStore();
        ArtifactToolOutputPolicy policy = ArtifactToolOutputPolicy.of(
                16, artifacts, (content, context) -> content.replace("secret", "***"));
        ExecutionContext context = ExecutionContext.at(Path.of("."));
        ToolInvocation invocation = ToolInvocation.create(
                new ToolUseId("artifact-1"), "Inspect", ToolArguments.empty());

        ToolResult result = policy.govern(
                invocation, ToolResult.ok("secret-" + "x".repeat(100)), context);

        assertThat(result.content()).hasSizeLessThan(120).contains("artifact://");
        assertThat(result.metadata())
                .containsEntry(ToolOutputMetadata.DISPOSITION_KEY, "truncated")
                .containsEntry(ToolOutputMetadata.ORIGINAL_CHARACTERS_KEY, "107")
                .containsEntry(ToolOutputMetadata.RETAINED_CHARACTERS_KEY, "16");
        String reference = result.metadata().get(ToolOutputMetadata.ARTIFACT_KEY);
        assertThat(reference).startsWith("artifact://");
        assertThat(artifacts.content).contains("x".repeat(100)).doesNotContain("secret");
        assertThat(artifacts.scope).isEqualTo(TaskScope.from(context));
    }

    private static final class MemoryArtifactStore implements ArtifactStore {
        private TaskScope scope;
        private String content;

        @Override
        public ArtifactReference write(TaskScope scope, String content) {
            this.scope = scope;
            this.content = content;
            ArtifactId id = ArtifactId.fresh();
            return new ArtifactReference(id, URI.create("artifact://" + id.value()),
                    content.length(), Instant.now().plusSeconds(60));
        }

        @Override
        public Optional<String> read(TaskScope scope, ArtifactReference reference) {
            return this.scope.equals(scope) ? Optional.of(content) : Optional.empty();
        }
    }
}
