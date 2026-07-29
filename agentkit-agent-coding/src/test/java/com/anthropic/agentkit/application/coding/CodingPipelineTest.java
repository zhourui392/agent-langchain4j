package com.anthropic.agentkit.application.coding;

import com.anthropic.agentkit.domain.agent.AgentRunContext;
import com.anthropic.agentkit.domain.coding.CodingPlan;
import com.anthropic.agentkit.domain.coding.CodingStatus;
import com.anthropic.agentkit.domain.coding.CodingTask;
import com.anthropic.agentkit.domain.coding.Patch;
import com.anthropic.agentkit.domain.coding.ReviewVerdict;
import com.anthropic.agentkit.domain.coding.Verdict;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class CodingPipelineTest {

    private final List<String> callLog = new ArrayList<>();
    private final CodingPlan plan = new CodingPlan("Add login page", List.of());
    private final Patch patch = new Patch("implement login", List.of());

    @Test
    void drivesPlanThenPatchThenReviewAndAcceptsTask() {
        CodingPipeline pipeline = pipelineWith(verdict(Verdict.ACCEPT));

        CodingTask result = pipeline.run(
                CodingTask.open("t1", "Add login page"), context());

        assertThat(callLog).containsExactly("plan", "patch", "review");
        assertThat(result.status()).isEqualTo(CodingStatus.ACCEPTED);
        assertThat(result.plan()).isSameAs(plan);
        assertThat(result.patch()).isSameAs(patch);
    }

    @Test
    void rejectVerdictLeavesTaskRejected() {
        CodingPipeline pipeline = pipelineWith(verdict(Verdict.REJECT));

        CodingTask result = pipeline.run(
                CodingTask.open("t1", "Add login page"), context());

        assertThat(result.status()).isEqualTo(CodingStatus.REJECTED);
    }

    @Test
    void feedsPlannerPlanToPatcherAndPatcherPatchToReviewer() {
        RecordingPatcher patcher = new RecordingPatcher();
        RecordingReviewer reviewer = new RecordingReviewer(verdict(Verdict.ACCEPT));
        CodingPipeline pipeline = new CodingPipeline(new RecordingPlanner(), patcher, reviewer);

        pipeline.run(CodingTask.open("t1", "Add login page"), context());

        assertThat(patcher.receivedPlan).isSameAs(plan);
        assertThat(reviewer.receivedPatch).isSameAs(patch);
    }

    private CodingPipeline pipelineWith(ReviewVerdict reviewVerdict) {
        return new CodingPipeline(new RecordingPlanner(), new RecordingPatcher(),
                new RecordingReviewer(reviewVerdict));
    }

    private static ReviewVerdict verdict(Verdict decision) {
        return new ReviewVerdict(decision, "summary", List.of());
    }

    private static AgentRunContext context() {
        return AgentRunContext.at(Path.of("."));
    }

    private final class RecordingPlanner implements CodingPlanner {
        @Override
        public CodingPlan createPlan(CodingTask task, AgentRunContext context) {
            callLog.add("plan");
            return plan;
        }
    }

    private final class RecordingPatcher implements CodingPatcher {
        private CodingPlan receivedPlan;

        @Override
        public Patch producePatch(CodingTask task, CodingPlan plan, AgentRunContext context) {
            callLog.add("patch");
            this.receivedPlan = plan;
            return patch;
        }
    }

    private final class RecordingReviewer implements CodingReviewer {
        private final ReviewVerdict reviewVerdict;
        private Patch receivedPatch;

        private RecordingReviewer(ReviewVerdict reviewVerdict) {
            this.reviewVerdict = reviewVerdict;
        }

        @Override
        public ReviewVerdict review(CodingTask task, Patch patch, AgentRunContext context) {
            callLog.add("review");
            this.receivedPatch = patch;
            return reviewVerdict;
        }
    }
}
