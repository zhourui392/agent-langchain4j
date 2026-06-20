package com.anthropic.agentkit.domain.coding;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CodingTaskTest {

    @Test
    void opensInPlanningState() {
        CodingTask task = CodingTask.open("task-1", "Add login page");

        assertThat(task.taskId()).isEqualTo("task-1");
        assertThat(task.requirement()).isEqualTo("Add login page");
        assertThat(task.status()).isEqualTo(CodingStatus.PLANNING);
        assertThat(task.plan()).isNull();
        assertThat(task.patch()).isNull();
        assertThat(task.verdict()).isNull();
    }

    @Test
    void adoptPlanMovesToCoding() {
        CodingTask task = CodingTask.open("task-1", "Add login page");
        CodingPlan plan = samplePlan();

        task.adoptPlan(plan);

        assertThat(task.status()).isEqualTo(CodingStatus.CODING);
        assertThat(task.plan()).isEqualTo(plan);
    }

    @Test
    void applyPatchMovesToReviewing() {
        CodingTask task = taskInCoding();

        task.applyPatch(samplePatch());

        assertThat(task.status()).isEqualTo(CodingStatus.REVIEWING);
        assertThat(task.patch()).isEqualTo(samplePatch());
    }

    @Test
    void acceptVerdictCompletes() {
        CodingTask task = taskInReviewing();

        task.recordVerdict(new ReviewVerdict(Verdict.ACCEPT, "looks good", List.of()));

        assertThat(task.status()).isEqualTo(CodingStatus.ACCEPTED);
        assertThat(task.verdict().decision()).isEqualTo(Verdict.ACCEPT);
    }

    @Test
    void rejectVerdictSetsRejected() {
        CodingTask task = taskInReviewing();

        task.recordVerdict(new ReviewVerdict(Verdict.REJECT, "broken", List.of("missing test")));

        assertThat(task.status()).isEqualTo(CodingStatus.REJECTED);
        assertThat(task.verdict().decision()).isEqualTo(Verdict.REJECT);
    }

    @Test
    void cannotApplyPatchBeforePlanAdopted() {
        CodingTask task = CodingTask.open("task-1", "Add login page");

        assertThatThrownBy(() -> task.applyPatch(samplePatch()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("PLANNING");
    }

    @Test
    void cannotRecordVerdictBeforePatchApplied() {
        CodingTask task = taskInCoding();

        assertThatThrownBy(() -> task.recordVerdict(
                new ReviewVerdict(Verdict.ACCEPT, "ok", List.of())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CODING");
    }

    @Test
    void cannotAdoptPlanAfterAccepted() {
        CodingTask task = taskInReviewing();
        task.recordVerdict(new ReviewVerdict(Verdict.ACCEPT, "ok", List.of()));

        assertThatThrownBy(() -> task.adoptPlan(samplePlan()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ACCEPTED");
    }

    @Test
    void codingPlanValidatesNonBlankProblemStatement() {
        assertThatThrownBy(() -> new CodingPlan("  ", List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void patchRejectsBlankSummary() {
        assertThatThrownBy(() -> new Patch("", List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void reviewVerdictRequiresDecision() {
        assertThatThrownBy(() -> new ReviewVerdict(null, "ok", List.of()))
                .isInstanceOf(NullPointerException.class);
    }

    private CodingTask taskInCoding() {
        CodingTask task = CodingTask.open("task-1", "Add login page");
        task.adoptPlan(samplePlan());
        return task;
    }

    private CodingTask taskInReviewing() {
        CodingTask task = taskInCoding();
        task.applyPatch(samplePatch());
        return task;
    }

    private static CodingPlan samplePlan() {
        return new CodingPlan("Add login page",
                List.of(new TaskItem("s-1", "write controller", List.of("src/Login.java"), TaskItemStatus.PENDING)));
    }

    private static Patch samplePatch() {
        return new Patch("add login controller",
                List.of(new FileChange("src/Login.java", FileChangeType.CREATE, "+class Login{}")));
    }
}
