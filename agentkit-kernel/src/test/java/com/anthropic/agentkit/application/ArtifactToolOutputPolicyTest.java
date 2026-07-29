package com.anthropic.agentkit.application;

import com.anthropic.agentkit.application.task.ArtifactContentPolicy;
import com.anthropic.agentkit.application.tool.ArtifactToolOutputPolicy;
import com.anthropic.agentkit.domain.port.ArtifactStore;
import com.anthropic.agentkit.domain.port.ArtifactStoreException;
import com.anthropic.agentkit.domain.task.ArtifactId;
import com.anthropic.agentkit.domain.task.ArtifactReference;
import com.anthropic.agentkit.domain.task.TaskScope;
import com.anthropic.agentkit.domain.tool.ExecutionContext;
import com.anthropic.agentkit.domain.tool.ToolArguments;
import com.anthropic.agentkit.domain.tool.ToolInvocation;
import com.anthropic.agentkit.domain.tool.ToolOutputMetadata;
import com.anthropic.agentkit.domain.tool.ToolResult;
import com.anthropic.agentkit.domain.tool.ToolResultStatus;
import com.anthropic.agentkit.domain.tool.ToolUseId;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
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

    @Test
    void artifactFailureReturnsBoundedPreviewWithExplicitOmission() {
        ArtifactToolOutputPolicy policy = ArtifactToolOutputPolicy.of(
                16, new FailingArtifactStore(), ArtifactContentPolicy.identity());
        ExecutionContext context = ExecutionContext.at(Path.of("."));
        ToolInvocation invocation = ToolInvocation.create(
                new ToolUseId("artifact-2"), "Inspect", ToolArguments.empty());

        ToolResult result = policy.govern(
                invocation, ToolResult.ok("x".repeat(100)), context);

        assertThat(result.content()).contains("artifact unavailable")
                .hasSizeLessThan(100);
        assertThat(result.metadata()).containsEntry(
                ToolOutputMetadata.ARTIFACT_KEY, ToolOutputMetadata.OMITTED);
    }

    @Test
    void alreadyTruncatedOutputIsNotRepublishedAsACompleteArtifact() {
        RejectingArtifactStore artifacts = new RejectingArtifactStore();
        ArtifactToolOutputPolicy policy = ArtifactToolOutputPolicy.of(
                16, artifacts, ArtifactContentPolicy.identity());
        ToolInvocation invocation = ToolInvocation.create(
                new ToolUseId("artifact-3"), "Inspect", ToolArguments.empty());
        ToolResult incomplete = ToolResult.of(
                ToolResultStatus.SUCCESS,
                "x".repeat(100),
                Map.of(
                        ToolOutputMetadata.DISPOSITION_KEY, ToolOutputMetadata.TRUNCATED,
                        ToolOutputMetadata.ORIGINAL_CHARACTERS_KEY, "1000",
                        ToolOutputMetadata.ARTIFACT_KEY, ToolOutputMetadata.OMITTED));

        ToolResult result = policy.govern(
                invocation, incomplete, ExecutionContext.at(Path.of(".")));

        assertThat(artifacts.writeAttempts).isZero();
        assertThat(result.metadata())
                .containsEntry(ToolOutputMetadata.ORIGINAL_CHARACTERS_KEY, "1000")
                .containsEntry(ToolOutputMetadata.ARTIFACT_KEY, ToolOutputMetadata.OMITTED);
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

    private static final class FailingArtifactStore implements ArtifactStore {
        @Override
        public ArtifactReference write(TaskScope scope, String content) {
            throw new ArtifactStoreException("artifact unavailable", null);
        }

        @Override
        public Optional<String> read(TaskScope scope, ArtifactReference reference) {
            return Optional.empty();
        }
    }

    private static final class RejectingArtifactStore implements ArtifactStore {
        private int writeAttempts;

        @Override
        public ArtifactReference write(TaskScope scope, String content) {
            writeAttempts++;
            throw new AssertionError("incomplete output must not be persisted as complete");
        }

        @Override
        public Optional<String> read(TaskScope scope, ArtifactReference reference) {
            return Optional.empty();
        }
    }
}
