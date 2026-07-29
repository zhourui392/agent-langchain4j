package com.anthropic.agentkit.infrastructure.coding;

import com.anthropic.agentkit.domain.agent.AgentRunContext;
import com.anthropic.agentkit.domain.coding.CodingTask;
import com.anthropic.agentkit.domain.coding.FileChange;
import com.anthropic.agentkit.domain.coding.FileChangeType;
import com.anthropic.agentkit.domain.coding.Patch;
import com.anthropic.agentkit.domain.coding.ReviewVerdict;
import com.anthropic.agentkit.domain.coding.Verdict;
import com.anthropic.agentkit.domain.message.AiMessage;
import com.anthropic.agentkit.domain.port.ToolSpec;
import com.anthropic.agentkit.domain.tool.ToolUseId;
import com.anthropic.agentkit.domain.tool.ToolUseRequest;
import com.anthropic.agentkit.testsupport.StubLlmClient;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class StructuredCodingReviewerTest {

    @Test
    void producesAcceptVerdict() {
        StubLlmClient llm = enqueueReview(
                "{\"decision\":\"ACCEPT\",\"summary\":\"looks good\"}");
        StructuredCodingReviewer reviewer = new StructuredCodingReviewer(llm);

        ReviewVerdict verdict = reviewer.review(task(), patch(), context());

        assertThat(verdict.decision()).isEqualTo(Verdict.ACCEPT);
        assertThat(verdict.summary()).isEqualTo("looks good");
        assertThat(verdict.issues()).isEmpty();
    }

    @Test
    void producesRejectVerdictWithIssues() {
        StubLlmClient llm = enqueueReview(
                "{\"decision\":\"REJECT\",\"summary\":\"bad\",\"issues\":[\"npe risk\",\"no tests\"]}");
        StructuredCodingReviewer reviewer = new StructuredCodingReviewer(llm);

        ReviewVerdict verdict = reviewer.review(task(), patch(), context());

        assertThat(verdict.decision()).isEqualTo(Verdict.REJECT);
        assertThat(verdict.issues()).containsExactly("npe risk", "no tests");
    }

    @Test
    void defaultsMissingDecisionToNeedsHuman() {
        StubLlmClient llm = enqueueReview("{\"summary\":\"unsure\"}");
        StructuredCodingReviewer reviewer = new StructuredCodingReviewer(llm);

        ReviewVerdict verdict = reviewer.review(task(), patch(), context());

        assertThat(verdict.decision()).isEqualTo(Verdict.NEEDS_HUMAN);
    }

    @Test
    void withholdsWriteToolsFromModel() {
        StubLlmClient llm = enqueueReview("{\"decision\":\"ACCEPT\",\"summary\":\"ok\"}");
        StructuredCodingReviewer reviewer = new StructuredCodingReviewer(llm);

        reviewer.review(task(), patch(), context());

        assertThat(llm.capturedRequests().get(0).tools())
                .extracting(ToolSpec::name)
                .containsExactly("submit_review");
    }

    private static StubLlmClient enqueueReview(String reviewJson) {
        return new StubLlmClient()
                .enqueue(new AiMessage("", List.of(new ToolUseRequest(
                        new ToolUseId("review-1"), "submit_review", reviewJson))))
                .enqueue(AiMessage.text("reviewed"));
    }

    private static CodingTask task() {
        return CodingTask.open("task-1", "Add login page");
    }

    private static Patch patch() {
        return new Patch("implement login", List.of(
                new FileChange("src/Login.java", FileChangeType.CREATE, "+class Login {}")));
    }

    private static AgentRunContext context() {
        return AgentRunContext.at(Path.of("."));
    }
}
