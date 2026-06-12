package com.anthropic.cclc.interfaces.engine;

import com.anthropic.cclc.application.AgentEventListener;
import com.anthropic.cclc.application.AgentExecutor;
import com.anthropic.cclc.application.InteractivePrompter;
import com.anthropic.cclc.application.PermissionService;
import com.anthropic.cclc.application.diagnosis.DiagnosisPlanner;
import com.anthropic.cclc.application.diagnosis.DiagnosisReporter;
import com.anthropic.cclc.application.diagnosis.PlanGuardMode;
import com.anthropic.cclc.application.diagnosis.PlanGuardPolicy;
import com.anthropic.cclc.domain.agent.AgentBudget;
import com.anthropic.cclc.domain.agent.AgentBudgetExceededException;
import com.anthropic.cclc.domain.conversation.CancellationToken;
import com.anthropic.cclc.domain.conversation.Conversation;
import com.anthropic.cclc.domain.diagnosis.DiagnosisCase;
import com.anthropic.cclc.domain.diagnosis.DiagnosisPlan;
import com.anthropic.cclc.domain.diagnosis.DiagnosisReport;
import com.anthropic.cclc.domain.diagnosis.DiagnosisStatus;
import com.anthropic.cclc.domain.message.AiMessage;
import com.anthropic.cclc.domain.permission.Decision;
import com.anthropic.cclc.domain.permission.PermissionMode;
import com.anthropic.cclc.domain.permission.PermissionPolicy;
import com.anthropic.cclc.domain.port.LlmClient;
import com.anthropic.cclc.domain.tool.ExecutionContext;
import com.anthropic.cclc.domain.tool.Tool;
import com.anthropic.cclc.domain.tool.ToolInvocation;
import com.anthropic.cclc.domain.tool.ToolRegistry;
import com.anthropic.cclc.domain.tool.ToolResult;
import com.anthropic.cclc.domain.tool.ToolUseRequest;
import com.anthropic.cclc.infrastructure.diagnosis.DiagnosisStateCodec;
import com.anthropic.cclc.infrastructure.permission.ReadOnlyPermissionPolicy;
import com.anthropic.cclc.infrastructure.streamjson.ClaudeStreamJsonListener;

import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletionException;
import java.util.function.Consumer;

/**
 * Diagnosis-specific orchestration around the kernel agent executor.
 *
 * @author zhourui(V33215020)
 * @since 2026-06-11
 */
public final class DiagnosisOrchestrator {

    private static final String DIAGNOSIS_SYSTEM_PROMPT = """
            You are an online incident diagnosis agent. Work read-only, define scope before querying,
            use the minimum necessary tools, and do not confirm a root cause without evidence.""";

    private static final InteractivePrompter REJECTING_PROMPTER = (invocation, tool) -> {
        throw new IllegalStateException("read-only diagnose engine has no interactive approval");
    };

    private final LlmClient llm;
    private final ToolRegistry tools;
    private final AgentBudget budget;
    private final DiagnosisStateCodec stateCodec;
    private final DiagnosisPlanner planner;
    private final DiagnosisReporter reporter;
    private final PlanGuardMode guardMode;
    private final String systemPrompt;

    public DiagnosisOrchestrator(LlmClient llm, ToolRegistry tools, AgentBudget budget,
                                 DiagnosisStateCodec stateCodec) {
        this(llm, tools, budget, stateCodec, null);
    }

    public DiagnosisOrchestrator(LlmClient llm, ToolRegistry tools, AgentBudget budget,
                                 DiagnosisStateCodec stateCodec, DiagnosisPlanner planner) {
        this(llm, tools, budget, stateCodec, planner, "");
    }

    public DiagnosisOrchestrator(LlmClient llm, ToolRegistry tools, AgentBudget budget,
                                 DiagnosisStateCodec stateCodec, DiagnosisPlanner planner,
                                 String promptPack) {
        this(llm, tools, new Options(budget, stateCodec, planner, null, PlanGuardMode.OBSERVE, promptPack));
    }

    public DiagnosisOrchestrator(LlmClient llm, ToolRegistry tools, Options options) {
        this.llm = Objects.requireNonNull(llm, "llm");
        this.tools = Objects.requireNonNull(tools, "tools");
        Options config = Objects.requireNonNull(options, "options");
        this.budget = config.budget();
        this.stateCodec = config.stateCodec();
        this.planner = config.planner();
        this.reporter = config.reporter();
        this.guardMode = config.guardMode();
        this.systemPrompt = composeSystemPrompt(config.promptPack(), config.skillsCatalog());
    }

    public void run(RunRequest request, Conversation conversation,
                    CancellationToken cancel, Consumer<String> onChunk) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(conversation, "conversation");
        Objects.requireNonNull(cancel, "cancel");
        Objects.requireNonNull(onChunk, "onChunk");

        DiagnosisStateListener listener = listener(request, onChunk);
        if (planIfConfigured(listener)) {
            listener.finish(AiMessage.text("Need more information before diagnosis."));
            return;
        }

        AgentExecutor executor = new AgentExecutor(llm, tools, permissions(listener), context(request, cancel), budget);
        try {
            executor.run(conversation, cancel, listener, systemPrompt).join();
        } catch (CompletionException ex) {
            handleRunFailure(listener, ex);
        }
    }

    private static String composeSystemPrompt(String promptPack) {
        return composeSystemPrompt(promptPack, "");
    }

    private static String composeSystemPrompt(String promptPack, String skillsCatalog) {
        StringBuilder sb = new StringBuilder(DIAGNOSIS_SYSTEM_PROMPT);
        appendPromptPack(sb, promptPack);
        appendSkillsCatalog(sb, skillsCatalog);
        return sb.toString();
    }

    private static void appendPromptPack(StringBuilder sb, String promptPack) {
        if (promptPack != null && !promptPack.isBlank()) {
            sb.append("\n\n## Diagnosis PromptPack\n").append(promptPack);
        }
    }

    private static void appendSkillsCatalog(StringBuilder sb, String skillsCatalog) {
        if (skillsCatalog != null && !skillsCatalog.isBlank()) {
            sb.append("\n\n## skills\n").append(skillsCatalog);
        }
    }

    private DiagnosisStateListener listener(RunRequest request, Consumer<String> onChunk) {
        DiagnosisCase diagnosisCase = restoreDiagnosisCase(request);
        return new DiagnosisStateListener(
                new ClaudeStreamJsonListener(request.sessionId(), request.workingDir(), onChunk),
                diagnosisCase,
                stateCodec,
                request.sessionId());
    }

    private boolean planIfConfigured(DiagnosisStateListener listener) {
        if (planner == null) {
            return false;
        }
        DiagnosisPlan plan = planner.createPlan(listener.diagnosisCase());
        listener.applyPlan(plan);
        return plan.needsMoreInformation();
    }

    private DiagnosisCase restoreDiagnosisCase(RunRequest request) {
        return stateCodec.decode(request.stateSnapshot())
                .orElseGet(() -> newRunningCase(request.sessionId(), request.userMessage()));
    }

    private static DiagnosisCase newRunningCase(String sessionId, String question) {
        DiagnosisCase diagnosisCase = DiagnosisCase.open(sessionId, question);
        diagnosisCase.adoptPlan(new DiagnosisPlan(question, List.of(), List.of()));
        return diagnosisCase;
    }

    private PermissionService permissions(DiagnosisStateListener listener) {
        return new PermissionService(
                policy(listener), REJECTING_PROMPTER, PermissionMode.BYPASS);
    }

    private PermissionPolicy policy(DiagnosisStateListener listener) {
        PermissionPolicy readOnly = new ReadOnlyPermissionPolicy();
        PermissionPolicy planGuard = new PlanGuardPolicy(() -> listener.diagnosisCase().plan(), guardMode);
        return (invocation, tool, mode) -> decide(readOnly, planGuard, invocation, tool, mode);
    }

    private static Decision decide(PermissionPolicy readOnly, PermissionPolicy planGuard,
                                   ToolInvocation invocation, Tool tool, PermissionMode mode) {
        Decision readOnlyDecision = readOnly.decide(invocation, tool, mode);
        if (readOnlyDecision != Decision.ALLOW) {
            return readOnlyDecision;
        }
        return planGuard.decide(invocation, tool, mode);
    }

    private static ExecutionContext context(RunRequest request, CancellationToken cancel) {
        return ExecutionContext.of(Paths.get(request.workingDir()), cancel);
    }

    private void handleRunFailure(DiagnosisStateListener listener, CompletionException failure) {
        Throwable cause = failure.getCause();
        if (cause instanceof AgentBudgetExceededException budgetExceeded) {
            listener.finishWithBudgetReport(budgetExceeded);
            return;
        }
        throw failure;
    }

    public record Options(AgentBudget budget, DiagnosisStateCodec stateCodec, DiagnosisPlanner planner,
                          DiagnosisReporter reporter, PlanGuardMode guardMode,
                          String promptPack, String skillsCatalog) {

        public Options(AgentBudget budget, DiagnosisStateCodec stateCodec, DiagnosisPlanner planner,
                       DiagnosisReporter reporter, PlanGuardMode guardMode, String promptPack) {
            this(budget, stateCodec, planner, reporter, guardMode, promptPack, "");
        }

        public Options {
            budget = Objects.requireNonNull(budget, "budget");
            stateCodec = Objects.requireNonNull(stateCodec, "stateCodec");
            guardMode = Objects.requireNonNull(guardMode, "guardMode");
            promptPack = promptPack == null ? "" : promptPack;
            skillsCatalog = skillsCatalog == null ? "" : skillsCatalog;
        }
    }

    private final class DiagnosisStateListener implements AgentEventListener {

        private final ClaudeStreamJsonListener delegate;
        private final DiagnosisCase diagnosisCase;
        private final DiagnosisStateCodec stateCodec;
        private final String sessionId;

        private DiagnosisStateListener(ClaudeStreamJsonListener delegate, DiagnosisCase diagnosisCase,
                                       DiagnosisStateCodec stateCodec, String sessionId) {
            this.delegate = delegate;
            this.diagnosisCase = diagnosisCase;
            this.stateCodec = stateCodec;
            this.sessionId = sessionId;
        }

        private DiagnosisCase diagnosisCase() {
            return diagnosisCase;
        }

        private void applyPlan(DiagnosisPlan plan) {
            diagnosisCase.adoptPlan(plan);
            emitPlan(plan);
            emitNeedInfoIfPresent(plan);
        }

        private void emitPlan(DiagnosisPlan plan) {
            delegate.emit("diagnosis_plan", Map.of(
                    "session_id", sessionId,
                    "plan", plan));
        }

        private void emitNeedInfoIfPresent(DiagnosisPlan plan) {
            if (!plan.needsMoreInformation()) {
                return;
            }
            diagnosisCase.requireInputs(plan.missingInputs());
            delegate.emit("diagnosis_need_info", Map.of(
                    "session_id", sessionId,
                    "missingInputs", plan.missingInputs()));
        }

        @Override
        public void onLlmRequestStart() {
            delegate.onLlmRequestStart();
        }

        @Override
        public void onAssistantTextDelta(String delta) {
            delegate.onAssistantTextDelta(delta);
        }

        @Override
        public void onToolUseStart(ToolUseRequest request) {
            delegate.onToolUseStart(request);
        }

        @Override
        public synchronized void onToolUseEnd(ToolUseRequest request, ToolResult result, long durationMs) {
            var evidence = diagnosisCase.recordToolEvidence(request, result);
            delegate.onToolUseEnd(request, result, durationMs);
            Optional.ofNullable(planner).ifPresent(diagnosisPlanner -> applyPlan(
                    diagnosisPlanner.updatePlan(diagnosisCase, evidence)));
        }

        @Override
        public void onUsage(int inputTokens, int outputTokens, int cacheReadInputTokens) {
            delegate.onUsage(inputTokens, outputTokens, cacheReadInputTokens);
        }

        @Override
        public void onTurnComplete(AiMessage finalMessage) {
            finish(finalMessage);
        }

        @Override
        public void onError(Throwable error) {
            delegate.onError(error);
        }

        private synchronized void finish(AiMessage finalMessage) {
            Optional.ofNullable(reporter).ifPresent(this::emitReport);
            if (diagnosisCase.status() == DiagnosisStatus.RUNNING) {
                diagnosisCase.markDone();
            }
            emitState();
            delegate.onTurnComplete(finalMessage);
        }

        private synchronized void finishWithBudgetReport(AgentBudgetExceededException error) {
            DiagnosisReport report = new DiagnosisReport(
                    "Diagnosis stopped because the configured budget was exceeded.",
                    List.of(), List.of(), List.of(),
                    List.of(error.getMessage()), 0.0, true);
            delegate.emit("diagnosis_report", Map.of("session_id", sessionId, "report", report));
            if (diagnosisCase.status() == DiagnosisStatus.RUNNING) {
                diagnosisCase.markDone();
            }
            emitState();
            delegate.onTurnComplete(AiMessage.text(report.summary()));
        }

        private void emitReport(DiagnosisReporter diagnosisReporter) {
            DiagnosisReport report = diagnosisReporter.report(diagnosisCase);
            delegate.emit("diagnosis_report", Map.of("session_id", sessionId, "report", report));
            if (!report.missingInformation().isEmpty()) {
                delegate.emit("diagnosis_need_info", Map.of(
                        "session_id", sessionId,
                        "missingInputs", report.missingInformation()));
            }
        }

        private void emitState() {
            delegate.emit("diagnosis_state", Map.of(
                    "session_id", sessionId,
                    "snapshot", stateCodec.encode(diagnosisCase)));
        }
    }
}
