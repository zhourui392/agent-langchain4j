package com.anthropic.agentkit.infrastructure.memory;

import com.anthropic.agentkit.domain.agent.RunId;
import com.anthropic.agentkit.domain.agent.WorkspaceId;
import com.anthropic.agentkit.domain.conversation.SessionId;
import com.anthropic.agentkit.domain.message.AiMessage;
import com.anthropic.agentkit.domain.permission.Decision;
import com.anthropic.agentkit.domain.port.RunSuspensionUnavailableException;
import com.anthropic.agentkit.domain.suspension.ApprovalRequest;
import com.anthropic.agentkit.domain.suspension.PlannedToolInvocation;
import com.anthropic.agentkit.domain.suspension.ResumeScope;
import com.anthropic.agentkit.domain.suspension.ResumeToken;
import com.anthropic.agentkit.domain.suspension.RunSuspension;
import com.anthropic.agentkit.domain.suspension.SuspensionId;
import com.anthropic.agentkit.domain.suspension.SuspensionKind;
import com.anthropic.agentkit.domain.suspension.SuspensionScope;
import com.anthropic.agentkit.domain.tool.ToolUseId;
import com.anthropic.agentkit.domain.tool.ToolUseRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FileRunSuspensionStoreTest {

    @Test
    void persistsNoRawTokenAndClaimsExactlyOnce(@TempDir Path directory) throws IOException {
        FileRunSuspensionStore store = new FileRunSuspensionStore(directory);
        RunSuspension suspension = approval();
        ResumeToken token = ResumeToken.fresh();

        store.save(suspension, token);
        String durable = durableContent(directory);
        RunSuspension claimed = store.claim(
                token, resumeScope("resume-1", WORKSPACE), SuspensionKind.APPROVAL);

        assertThat(durable).doesNotContain(token.value()).contains("write-1", "Write");
        assertThat(claimed).isEqualTo(suspension);
        assertUnavailable(() -> store.claim(
                token, resumeScope("resume-2", WORKSPACE), SuspensionKind.APPROVAL));
    }

    @Test
    void wrongScopeOrKindDoesNotConsumeToken(@TempDir Path directory) {
        FileRunSuspensionStore store = new FileRunSuspensionStore(directory);
        RunSuspension suspension = approval();
        ResumeToken token = ResumeToken.fresh();
        store.save(suspension, token);

        assertUnavailable(() -> store.claim(
                token, resumeScope("wrong-workspace", WorkspaceId.of("other")),
                SuspensionKind.APPROVAL));
        assertUnavailable(() -> store.claim(
                token, resumeScope("wrong-kind", WORKSPACE), SuspensionKind.INPUT));

        assertThat(store.claim(
                token, resumeScope("correct", WORKSPACE), SuspensionKind.APPROVAL))
                .isEqualTo(suspension);
    }

    @Test
    void concurrentClaimsHaveOneWinner(@TempDir Path directory) throws Exception {
        FileRunSuspensionStore first = new FileRunSuspensionStore(directory);
        FileRunSuspensionStore second = new FileRunSuspensionStore(directory);
        ResumeToken token = ResumeToken.fresh();
        first.save(approval(), token);
        CountDownLatch start = new CountDownLatch(1);

        try (var pool = Executors.newFixedThreadPool(2)) {
            var a = pool.submit(() -> claimAfter(start, first, token, "race-a"));
            var b = pool.submit(() -> claimAfter(start, second, token, "race-b"));
            start.countDown();
            assertThat(List.of(a.get(), b.get())).containsExactlyInAnyOrder(true, false);
        }
    }

    private static boolean claimAfter(
            CountDownLatch start, FileRunSuspensionStore store,
            ResumeToken token, String runId) throws InterruptedException {
        start.await();
        try {
            store.claim(token, resumeScope(runId, WORKSPACE), SuspensionKind.APPROVAL);
            return true;
        } catch (RunSuspensionUnavailableException unavailable) {
            return false;
        }
    }

    private static RunSuspension approval() {
        ToolUseRequest request = new ToolUseRequest(
                new ToolUseId("write-1"), "Write", "{\"path\":\"a.txt\"}");
        ApprovalRequest approval = new ApprovalRequest(List.of(
                new PlannedToolInvocation(request, Decision.ASK)));
        return new RunSuspension.WaitingForApproval(
                SuspensionId.of("suspension-1"),
                new SuspensionScope(SESSION, WORKSPACE, RunId.of("origin")),
                approval, AiMessage.of("write", List.of(request)));
    }

    private static ResumeScope resumeScope(String runId, WorkspaceId workspaceId) {
        return new ResumeScope(RunId.of(runId), SESSION, workspaceId);
    }

    private static String read(Path path) {
        try {
            return path.getFileName() + Files.readString(path);
        } catch (IOException failure) {
            throw new AssertionError(failure);
        }
    }

    private static String durableContent(Path directory) throws IOException {
        try (var files = Files.walk(directory)) {
            return files.filter(Files::isRegularFile)
                    .map(FileRunSuspensionStoreTest::read)
                    .reduce("", String::concat);
        }
    }

    private static void assertUnavailable(Runnable operation) {
        assertThatThrownBy(operation::run)
                .isInstanceOf(RunSuspensionUnavailableException.class)
                .hasMessageNotContaining("other")
                .hasMessageNotContaining("wrong")
                .hasMessageNotContaining("token");
    }

    private static final SessionId SESSION = SessionId.of("session");
    private static final WorkspaceId WORKSPACE = WorkspaceId.of("workspace");
}
