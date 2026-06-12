package com.anthropic.cclc.interfaces.engine;

import com.anthropic.cclc.application.context.ContextCompactionService;
import com.anthropic.cclc.application.diagnosis.DiagnosisPlanner;
import com.anthropic.cclc.application.diagnosis.DiagnosisReporter;
import com.anthropic.cclc.application.diagnosis.PlanGuardMode;
import com.anthropic.cclc.domain.agent.AgentBudget;
import com.anthropic.cclc.domain.conversation.Conversation;
import com.anthropic.cclc.domain.conversation.SessionId;
import com.anthropic.cclc.domain.conversation.TokenBudget;
import com.anthropic.cclc.domain.port.LlmClient;
import com.anthropic.cclc.domain.tool.ToolRegistry;
import com.anthropic.cclc.infrastructure.diagnosis.DiagnosisStateCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Default {@link DiagnoseEngine}: rebuilds a stateless conversation, runs the
 * shared {@code AgentExecutor} under a read-only permission policy, and streams
 * events as Claude {@code stream-json}.
 *
 * <p>{@code runStream} blocks until the turn finishes. The read-only policy is
 * hardcoded -- the engine's identity is diagnosis, so it can never be configured
 * to mutate. The interactive prompter is never reached (the policy never asks).
 *
 * @author zhourui(V33215020)
 * @since 2026-06-08
 */
public final class DefaultDiagnoseEngine implements DiagnoseEngine {

    private static final int DEFAULT_CONTEXT_TOKENS = 180_000;
    private static final int DEFAULT_RECENT_MESSAGES = 30;
    private static final int DEFAULT_CLOSE_DRAIN_SECONDS = 10;
    private static final Logger log = LoggerFactory.getLogger(DefaultDiagnoseEngine.class);

    private final ConversationRebuilder rebuilder = new ConversationRebuilder();
    private final ContextCompactionService compaction;
    private final DiagnosisOrchestrator orchestrator;
    private final RunningSessions running = new RunningSessions();
    private final ScheduledExecutorService timeoutScheduler =
            Executors.newSingleThreadScheduledExecutor(DefaultDiagnoseEngine::daemon);
    private final Semaphore concurrency;
    private final long closeDrainSeconds;
    private final AtomicBoolean closed = new AtomicBoolean();

    public DefaultDiagnoseEngine(LlmClient llm, ToolRegistry tools) {
        this(llm, tools, AgentBudget.unlimited());
    }

    public DefaultDiagnoseEngine(LlmClient llm, ToolRegistry tools, AgentBudget budget) {
        this(llm, tools, budget, null);
    }

    public DefaultDiagnoseEngine(LlmClient llm, ToolRegistry tools, AgentBudget budget,
                                 DiagnosisPlanner planner) {
        this(llm, tools, budget, planner, "");
    }

    public DefaultDiagnoseEngine(LlmClient llm, ToolRegistry tools, AgentBudget budget,
                                 DiagnosisPlanner planner, String promptPack) {
        this(llm, tools, options(budget, planner, null, PlanGuardMode.OBSERVE, promptPack));
    }

    public DefaultDiagnoseEngine(LlmClient llm, ToolRegistry tools, EngineOptions options) {
        Objects.requireNonNull(tools, "tools");
        EngineOptions config = Objects.requireNonNull(options, "options");
        this.compaction = new ContextCompactionService(
                llm, TokenBudget.of(DEFAULT_CONTEXT_TOKENS), DEFAULT_RECENT_MESSAGES);
        this.orchestrator = new DiagnosisOrchestrator(llm, tools, new DiagnosisOrchestrator.Options(
                config.budget(),
                new DiagnosisStateCodec(),
                config.planner(),
                config.reporter(),
                config.guardMode(),
                config.promptPack(),
                config.skillsCatalog()));
        this.concurrency = semaphore(config.maxConcurrentRuns());
        this.closeDrainSeconds = config.closeDrainSeconds();
    }

    @Override
    public void run(RunRequest request, Consumer<String> onChunk, Consumer<RunSummary> onComplete) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(onChunk, "onChunk");
        Objects.requireNonNull(onComplete, "onComplete");
        String sessionId = request.sessionId();
        RunStart start = tryStartRun(sessionId, onComplete);
        if (start.rejected()) {
            return;
        }
        Conversation conversation = compaction.maybeCompact(rebuilder.from(
                SessionId.of(sessionId), request.history(), request.userMessage()));
        long startedAt = System.nanoTime();
        log.info("diagnose run started sessionId={} workingDir={} historySize={} hasSnapshot={}",
                sessionId, request.workingDir(), request.history().size(), !request.stateSnapshot().isBlank());
        try {
            OrchestrationResult result = await(request, conversation, start.control(), onChunk);
            RunSummary summary = summary(ExitReason.SUCCESS, result, "");
            logCompletion(sessionId, startedAt, summary);
            onComplete.accept(summary);
        } catch (RuntimeException ex) {
            RunSummary summary = summary(exitReason(start.control()), null, errorDetail(ex));
            logIfUnexpectedFailure(sessionId, summary, ex);
            logCompletion(sessionId, startedAt, summary);
            onComplete.accept(summary);
        } finally {
            finishRun(sessionId, start.control());
        }
    }

    @Override
    public void stop(String sessionId) {
        running.cancel(sessionId);
    }

    @Override
    public boolean isRunning(String sessionId) {
        return running.isRunning(sessionId);
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        running.cancelAll();
        awaitDrain();
        timeoutScheduler.shutdownNow();
    }

    private RunStart tryStartRun(String sessionId, Consumer<RunSummary> onComplete) {
        if (closed.get()) {
            reject(onComplete, "engine is closed");
            return RunStart.rejectedStart();
        }
        if (!acquireConcurrency()) {
            reject(onComplete, "max concurrent diagnosis runs reached");
            return RunStart.rejectedStart();
        }
        return registerSession(sessionId, onComplete);
    }

    private RunStart registerSession(String sessionId, Consumer<RunSummary> onComplete) {
        return running.register(sessionId)
                .map(RunStart::started)
                .orElseGet(() -> {
                    releaseConcurrency();
                    reject(onComplete, "session is already running: " + sessionId);
                    return RunStart.rejectedStart();
                });
    }

    private boolean acquireConcurrency() {
        return concurrency == null || concurrency.tryAcquire();
    }

    private void releaseConcurrency() {
        if (concurrency != null) {
            concurrency.release();
        }
    }

    private void reject(Consumer<RunSummary> onComplete, String reason) {
        onComplete.accept(new RunSummary(ExitReason.REJECTED, "", RunSummary.Usage.zero(), reason));
    }

    private void finishRun(String sessionId, RunningSessions.RunControl control) {
        running.remove(sessionId, control);
        releaseConcurrency();
    }

    private OrchestrationResult await(RunRequest request, Conversation conversation,
                                      RunningSessions.RunControl control, Consumer<String> onChunk) {
        ScheduledFuture<?> timeout = scheduleTimeout(control, request.timeoutSeconds());
        try {
            return orchestrator.run(request, conversation, control.token(), onChunk);
        } finally {
            if (timeout != null) {
                timeout.cancel(false);
            }
        }
    }

    private ScheduledFuture<?> scheduleTimeout(RunningSessions.RunControl control, long timeoutSeconds) {
        if (timeoutSeconds <= 0) {
            return null;
        }
        return timeoutScheduler.schedule(control::timeout, timeoutSeconds, TimeUnit.SECONDS);
    }

    private static RunSummary summary(ExitReason reason, OrchestrationResult result, String errorDetail) {
        if (result == null) {
            return new RunSummary(reason, "", RunSummary.Usage.zero(), errorDetail);
        }
        return new RunSummary(reason, result.stateSnapshot(), result.usage(), errorDetail);
    }

    private static ExitReason exitReason(RunningSessions.RunControl control) {
        if (control.isTimedOut()) {
            return ExitReason.TIMEOUT;
        }
        if (control.isCancelled()) {
            return ExitReason.STOPPED;
        }
        return ExitReason.ERROR;
    }

    private static String errorDetail(RuntimeException ex) {
        String message = ex.getMessage();
        if (message == null || message.isBlank()) {
            return ex.getClass().getSimpleName();
        }
        return message;
    }

    private static void logCompletion(String sessionId, long startedAt, RunSummary summary) {
        long durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
        RunSummary.Usage usage = summary.usage();
        log.info("diagnose run completed sessionId={} reason={} durationMs={} inputTokens={} "
                        + "outputTokens={} cacheReadInputTokens={}",
                sessionId, summary.reason(), durationMs, usage.inputTokens(),
                usage.outputTokens(), usage.cacheReadInputTokens());
    }

    private static void logIfUnexpectedFailure(String sessionId, RunSummary summary, RuntimeException ex) {
        if (summary.reason() == ExitReason.ERROR) {
            log.error("diagnose run failed sessionId={}", sessionId, ex);
        }
    }

    private static Thread daemon(Runnable runnable) {
        Thread thread = new Thread(runnable, "diagnose-timeout");
        thread.setDaemon(true);
        return thread;
    }

    private void awaitDrain() {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(closeDrainSeconds);
        while (running.size() > 0 && System.nanoTime() < deadline) {
            sleepQuietly();
        }
        if (running.size() > 0) {
            log.warn("diagnose engine close timed out with runningSessions={}", running.size());
        }
    }

    private static void sleepQuietly() {
        try {
            Thread.sleep(10);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    private static Semaphore semaphore(int maxConcurrentRuns) {
        if (maxConcurrentRuns <= 0) {
            return null;
        }
        return new Semaphore(maxConcurrentRuns);
    }

    private static EngineOptions options(AgentBudget budget, DiagnosisPlanner planner,
                                         DiagnosisReporter reporter, PlanGuardMode guardMode,
                                         String promptPack) {
        return new EngineOptions(budget, planner, reporter, guardMode, promptPack);
    }

    public record EngineOptions(AgentBudget budget, DiagnosisPlanner planner, DiagnosisReporter reporter,
                                PlanGuardMode guardMode, String promptPack, String skillsCatalog,
                                int maxConcurrentRuns, long closeDrainSeconds) {

        public EngineOptions(AgentBudget budget, DiagnosisPlanner planner, DiagnosisReporter reporter,
                             PlanGuardMode guardMode, String promptPack) {
            this(budget, planner, reporter, guardMode, promptPack, "", 0, DEFAULT_CLOSE_DRAIN_SECONDS);
        }

        public EngineOptions(AgentBudget budget, DiagnosisPlanner planner, DiagnosisReporter reporter,
                             PlanGuardMode guardMode, String promptPack, String skillsCatalog) {
            this(budget, planner, reporter, guardMode, promptPack,
                    skillsCatalog, 0, DEFAULT_CLOSE_DRAIN_SECONDS);
        }

        public EngineOptions(AgentBudget budget, DiagnosisPlanner planner, DiagnosisReporter reporter,
                             PlanGuardMode guardMode, String promptPack,
                             int maxConcurrentRuns, long closeDrainSeconds) {
            this(budget, planner, reporter, guardMode, promptPack,
                    "", maxConcurrentRuns, closeDrainSeconds);
        }

        public EngineOptions {
            Objects.requireNonNull(budget, "budget");
            Objects.requireNonNull(guardMode, "guardMode");
            promptPack = promptPack == null ? "" : promptPack;
            skillsCatalog = skillsCatalog == null ? "" : skillsCatalog;
            if (closeDrainSeconds <= 0) {
                closeDrainSeconds = DEFAULT_CLOSE_DRAIN_SECONDS;
            }
        }
    }

    private record RunStart(RunningSessions.RunControl control, boolean rejected) {
        private static RunStart started(RunningSessions.RunControl control) {
            return new RunStart(control, false);
        }

        private static RunStart rejectedStart() {
            return new RunStart(null, true);
        }
    }
}
