package com.anthropic.agentkit.interfaces.engine;

import com.anthropic.agentkit.application.AgentEventListener;
import com.anthropic.agentkit.application.RequiredAgentEventListener;
import com.anthropic.agentkit.application.AgentExecutor;
import com.anthropic.agentkit.application.InteractivePrompter;
import com.anthropic.agentkit.application.PermissionService;
import com.anthropic.agentkit.application.diagnosis.DiagnosisPlanner;
import com.anthropic.agentkit.application.diagnosis.DiagnosisReporter;
import com.anthropic.agentkit.application.diagnosis.PlanGuardMode;
import com.anthropic.agentkit.application.diagnosis.PlanGuardPolicy;
import com.anthropic.agentkit.domain.agent.AgentBudget;
import com.anthropic.agentkit.domain.agent.AgentRunContext;
import com.anthropic.agentkit.domain.agent.AgentRunLimits;
import com.anthropic.agentkit.domain.agent.RunDeadline;
import com.anthropic.agentkit.domain.agent.AgentRunResult;
import com.anthropic.agentkit.domain.agent.AgentUsage;
import com.anthropic.agentkit.domain.agent.BudgetConsumption;
import com.anthropic.agentkit.domain.agent.StopReason;
import com.anthropic.agentkit.domain.conversation.CancellationToken;
import com.anthropic.agentkit.domain.conversation.Conversation;
import com.anthropic.agentkit.domain.diagnosis.DiagnosisCase;
import com.anthropic.agentkit.domain.diagnosis.DiagnosisBlocker;
import com.anthropic.agentkit.domain.diagnosis.DiagnosisBlockerType;
import com.anthropic.agentkit.domain.diagnosis.DiagnosisExecutionCapabilities;
import com.anthropic.agentkit.domain.diagnosis.DiagnosisResourceCatalog;
import com.anthropic.agentkit.domain.diagnosis.DiagnosisResourceCatalogSnapshot;
import com.anthropic.agentkit.domain.diagnosis.DiagnosisPlan;
import com.anthropic.agentkit.domain.diagnosis.DiagnosisReport;
import com.anthropic.agentkit.domain.diagnosis.DiagnosisStatus;
import com.anthropic.agentkit.domain.diagnosis.OperationalContext;
import com.anthropic.agentkit.domain.message.AiMessage;
import com.anthropic.agentkit.domain.permission.Decision;
import com.anthropic.agentkit.domain.permission.PermissionMode;
import com.anthropic.agentkit.domain.permission.PermissionPolicy;
import com.anthropic.agentkit.domain.port.LlmClient;
import com.anthropic.agentkit.domain.tool.Tool;
import com.anthropic.agentkit.domain.tool.ToolInvocation;
import com.anthropic.agentkit.domain.tool.ToolRegistry;
import com.anthropic.agentkit.domain.tool.ToolRegistrySnapshot;
import com.anthropic.agentkit.domain.tool.ToolResult;
import com.anthropic.agentkit.domain.tool.ToolUseRequest;
import com.anthropic.agentkit.infrastructure.diagnosis.DiagnosisStateCodec;
import com.anthropic.agentkit.infrastructure.permission.ReadOnlyPermissionPolicy;
import com.anthropic.agentkit.infrastructure.streamjson.ClaudeStreamJsonListener;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.stream.Collectors;

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
    private final DiagnosisResourceCatalog resourceCatalog;

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
        this.resourceCatalog = config.resourceCatalog();
        this.systemPrompt = composeSystemPrompt(config.promptPack(), config.skillsCatalog());
    }

    public OrchestrationResult run(RunRequest request, Conversation conversation,
                                   CancellationToken cancel, Consumer<String> onChunk) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(conversation, "conversation");
        Objects.requireNonNull(cancel, "cancel");
        Objects.requireNonNull(onChunk, "onChunk");

        AgentRunContext context = context(request, conversation, cancel);
        ToolRegistrySnapshot toolSnapshot = tools.snapshot(context.executionContext());
        DiagnosisResourceCatalogSnapshot resourceSnapshot = resourceCatalog.snapshot();
        OperationalContext operationalContext = request.operationalContext()
                .withResources(resourceSnapshot);
        DiagnosisExecutionCapabilities capabilities = new DiagnosisExecutionCapabilities(
                toolSnapshot.generation(), toolSnapshot.names(), resourceSnapshot);
        DiagnosisStateListener listener = listener(
                request, context, operationalContext, capabilities, onChunk);
        if (planTerminatesBeforeExecution(listener, request, operationalContext)) {
            AiMessage message = planningTerminalMessage(listener.diagnosisCase());
            listener.finishWaiting(message);
            return listener.result(planningTerminal(context, listener.diagnosisCase(), message));
        }

        AgentExecutor executor = new AgentExecutor(
                llm, toolSnapshot.frozenRegistry(), permissions(listener));
        AgentRunResult result = executor.run(
                conversation, context, listener,
                executionSystemPrompt(listener.diagnosisCase().plan())).join();
        if (listener.isPlanningTerminal()) {
            return finishPlanningTerminal(listener, result);
        }
        finishNonModelStop(listener, result);
        return listener.result(result);
    }

    private OrchestrationResult finishPlanningTerminal(
            DiagnosisStateListener listener, AgentRunResult priorResult) {
        AiMessage message = planningTerminalMessage(listener.diagnosisCase());
        listener.finishWaiting(message);
        StopReason reason = listener.diagnosisCase().status() == DiagnosisStatus.NEED_INFO
                ? StopReason.WAITING_FOR_INPUT : StopReason.TERMINAL_TOOL;
        AgentRunResult result = new AgentRunResult(
                priorResult.runId(), reason, message, Optional.empty(),
                priorResult.usage(), priorResult.consumption());
        return listener.result(result);
    }

    private static AgentRunResult waitingForInput(
            AgentRunContext context, AiMessage finalMessage) {
        return new AgentRunResult(
                context.runId(), StopReason.WAITING_FOR_INPUT, finalMessage,
                Optional.empty(), AgentUsage.zero(), BudgetConsumption.zero());
    }

    private static AgentRunResult planningTerminal(
            AgentRunContext context, DiagnosisCase diagnosisCase, AiMessage finalMessage) {
        if (diagnosisCase.status() == DiagnosisStatus.NEED_INFO) {
            return waitingForInput(context, finalMessage);
        }
        return new AgentRunResult(
                context.runId(), StopReason.TERMINAL_TOOL, finalMessage,
                Optional.empty(), AgentUsage.zero(), BudgetConsumption.zero());
    }

    private static void finishNonModelStop(
            DiagnosisStateListener listener, AgentRunResult result) {
        if (result.stopReason() == StopReason.MODEL_COMPLETED) {
            return;
        }
        if (result.stopReason() == StopReason.BUDGET_EXHAUSTED) {
            listener.finishWithBudgetReport("agent budget exhausted");
            return;
        }
        listener.finish(result.finalMessage());
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

    private DiagnosisStateListener listener(RunRequest request, AgentRunContext context,
                                            OperationalContext operationalContext,
                                            DiagnosisExecutionCapabilities capabilities,
                                            Consumer<String> onChunk) {
        DiagnosisCase diagnosisCase = restoreDiagnosisCase(request);
        return new DiagnosisStateListener(
                new ClaudeStreamJsonListener(request.sessionId(), request.workingDir(), onChunk),
                diagnosisCase, context, operationalContext, capabilities,
                planningContext(request), stateCodec, request.sessionId());
    }

    private boolean planTerminatesBeforeExecution(
            DiagnosisStateListener listener, RunRequest request,
            OperationalContext operationalContext) {
        if (planner == null) {
            return false;
        }
        DiagnosisPlan plan = planner.createPlan(
                listener.diagnosisCase(), planningContext(request),
                operationalContext, listener.capabilities(), listener.context());
        listener.applyInitialPlan(plan);
        return listener.diagnosisCase().status() == DiagnosisStatus.NEED_INFO
                || listener.diagnosisCase().status() == DiagnosisStatus.BLOCKED;
    }

    private String executionSystemPrompt(DiagnosisPlan plan) {
        if (plan == null || !plan.scope().isKnown()) {
            return systemPrompt;
        }
        StringBuilder prompt = new StringBuilder(systemPrompt)
                .append("\n\n## Host-approved diagnosis scope\n")
                .append("environment=").append(plan.scope().environment().name()).append('\n')
                .append("services=").append(String.join(",", plan.scope().services())).append('\n');
        appendTimeWindow(prompt, plan);
        prompt.append("allowedTools=").append(allowedTools(plan)).append('\n')
                .append("identifiers=").append(plan.scope().identifiers()).append('\n')
                .append("Use these exact service and absolute time bounds in every LogQuery call. ")
                .append("Never widen this scope or invent a later endTime.");
        return prompt.toString();
    }

    private static void appendTimeWindow(StringBuilder prompt, DiagnosisPlan plan) {
        if (!plan.scope().timeWindow().isKnown()) {
            prompt.append("startTime=unknown\nendTime=unknown\n");
            return;
        }
        prompt.append("startTime=").append(
                        plan.scope().timeWindow().startInclusive()).append('\n')
                .append("endTime=").append(
                        plan.scope().timeWindow().endExclusive()).append('\n');
    }

    private static String allowedTools(DiagnosisPlan plan) {
        return plan.steps().stream().flatMap(step -> step.allowedTools().stream())
                .distinct().sorted().collect(Collectors.joining(","));
    }

    private static String planningContext(RunRequest request) {
        StringBuilder context = new StringBuilder();
        for (TurnMessage turn : request.history()) {
            if (turn instanceof UserTurn user) {
                context.append("Previous user input: ").append(user.text()).append('\n');
            }
        }
        context.append("Current user input: ").append(request.userMessage());
        return context.toString();
    }

    private static AiMessage missingInputMessage(DiagnosisCase diagnosisCase) {
        List<String> missingInputs = diagnosisCase.plan().missingInputs().stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .distinct()
                .toList();
        String heading = containsCjk(diagnosisCase.question())
                ? "继续诊断还需要以下信息："
                : "To continue the diagnosis, please provide:";
        String questions = missingInputs.stream()
                .map(value -> "- " + value)
                .collect(Collectors.joining("\n"));
        return AiMessage.text(heading + "\n" + questions);
    }

    private static AiMessage planningTerminalMessage(DiagnosisCase diagnosisCase) {
        return diagnosisCase.status() == DiagnosisStatus.BLOCKED
                ? blockerMessage(diagnosisCase) : missingInputMessage(diagnosisCase);
    }

    private static AiMessage blockerMessage(DiagnosisCase diagnosisCase) {
        boolean chinese = containsCjk(diagnosisCase.question());
        String heading = chinese ? "当前诊断无法继续执行：" : "The diagnosis cannot proceed:";
        String details = diagnosisCase.blockers().stream()
                .map(blocker -> blockerLine(blocker, chinese))
                .collect(Collectors.joining("\n"));
        return AiMessage.text(heading + "\n" + details);
    }

    private static String blockerLine(DiagnosisBlocker blocker, boolean chinese) {
        String remediation = blocker.remediation().isBlank()
                ? "" : (chinese ? "；处理方式：" : "; remediation: ") + blocker.remediation();
        return "- [" + blocker.code() + "] " + blocker.message() + remediation;
    }

    private static boolean containsCjk(String value) {
        return value.codePoints().anyMatch(codePoint ->
                Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN);
    }

    private DiagnosisCase restoreDiagnosisCase(RunRequest request) {
        return stateCodec.decode(request.stateSnapshot())
                .map(DiagnosisOrchestrator::startFollowUpIfCompleted)
                .orElseGet(() -> newRunningCase(request.sessionId(), request.userMessage()));
    }

    private static DiagnosisCase startFollowUpIfCompleted(DiagnosisCase diagnosisCase) {
        if (diagnosisCase.status() == DiagnosisStatus.DONE) {
            diagnosisCase.startFollowUp();
        }
        return diagnosisCase;
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

    private AgentRunContext context(RunRequest request, Conversation conversation,
                                    CancellationToken cancel) {
        AgentRunContext context = AgentRunContext.create(
                conversation.sessionId(), Path.of(request.workingDir()), cancel, budget);
        if (request.timeoutSeconds() <= 0) {
            return context;
        }
        return context.withLimits(new AgentRunLimits(
                RunDeadline.after(Duration.ofSeconds(request.timeoutSeconds())),
                AgentRunLimits.DEFAULT_PROVIDER_TIMEOUT,
                AgentRunLimits.DEFAULT_TOOL_TIMEOUT));
    }

    public record Options(AgentBudget budget, DiagnosisStateCodec stateCodec, DiagnosisPlanner planner,
                          DiagnosisReporter reporter, PlanGuardMode guardMode,
                          String promptPack, String skillsCatalog,
                          DiagnosisResourceCatalog resourceCatalog) {

        public Options(AgentBudget budget, DiagnosisStateCodec stateCodec, DiagnosisPlanner planner,
                       DiagnosisReporter reporter, PlanGuardMode guardMode,
                       String promptPack, String skillsCatalog) {
            this(budget, stateCodec, planner, reporter, guardMode, promptPack,
                    skillsCatalog, DiagnosisResourceCatalog.empty());
        }

        public Options(AgentBudget budget, DiagnosisStateCodec stateCodec, DiagnosisPlanner planner,
                       DiagnosisReporter reporter, PlanGuardMode guardMode, String promptPack) {
            this(budget, stateCodec, planner, reporter, guardMode, promptPack, "",
                    DiagnosisResourceCatalog.empty());
        }

        public Options {
            budget = Objects.requireNonNull(budget, "budget");
            stateCodec = Objects.requireNonNull(stateCodec, "stateCodec");
            guardMode = Objects.requireNonNull(guardMode, "guardMode");
            promptPack = promptPack == null ? "" : promptPack;
            skillsCatalog = skillsCatalog == null ? "" : skillsCatalog;
            resourceCatalog = resourceCatalog == null
                    ? DiagnosisResourceCatalog.empty() : resourceCatalog;
        }
    }

    private final class DiagnosisStateListener implements RequiredAgentEventListener {

        private final ClaudeStreamJsonListener delegate;
        private final DiagnosisCase diagnosisCase;
        private final AgentRunContext context;
        private final OperationalContext operationalContext;
        private final DiagnosisExecutionCapabilities capabilities;
        private final String conversationContext;
        private final DiagnosisStateCodec stateCodec;
        private final String sessionId;
        private String stateSnapshot = "";

        private DiagnosisStateListener(ClaudeStreamJsonListener delegate, DiagnosisCase diagnosisCase,
                                       AgentRunContext context,
                                       OperationalContext operationalContext,
                                       DiagnosisExecutionCapabilities capabilities,
                                       String conversationContext,
                                       DiagnosisStateCodec stateCodec, String sessionId) {
            this.delegate = delegate;
            this.diagnosisCase = diagnosisCase;
            this.context = context;
            this.operationalContext = operationalContext;
            this.capabilities = capabilities;
            this.conversationContext = conversationContext;
            this.stateCodec = stateCodec;
            this.sessionId = sessionId;
        }

        private DiagnosisCase diagnosisCase() {
            return diagnosisCase;
        }

        private AgentRunContext context() {
            return context;
        }

        private DiagnosisExecutionCapabilities capabilities() {
            return capabilities;
        }

        private void applyInitialPlan(DiagnosisPlan plan) {
            applyPlan(plan, false);
        }

        private void applyUpdatedPlan(DiagnosisPlan plan) {
            applyPlan(plan, true);
        }

        private void applyPlan(DiagnosisPlan plan, boolean stopOnTerminal) {
            diagnosisCase.adoptPlan(plan);
            emitPlan(plan);
            emitBlockersIfPresent(plan);
            emitNeedInfoIfPresent(plan);
            if (stopOnTerminal && isPlanningTerminal()) {
                context.cancellation().cancel();
            }
        }

        private boolean isPlanningTerminal() {
            return diagnosisCase.status() == DiagnosisStatus.NEED_INFO
                    || diagnosisCase.status() == DiagnosisStatus.BLOCKED;
        }

        private void emitPlan(DiagnosisPlan plan) {
            delegate.emit("diagnosis_plan", Map.of(
                    "session_id", sessionId,
                    "plan", plan));
        }

        private void emitNeedInfoIfPresent(DiagnosisPlan plan) {
            if (plan.isBlocked() || !plan.needsMoreInformation()) {
                return;
            }
            diagnosisCase.requireInputs(plan.missingInputs());
            delegate.emit("diagnosis_need_info", Map.of(
                    "session_id", sessionId,
                    "missingInputs", plan.missingInputs()));
        }

        private void emitBlockersIfPresent(DiagnosisPlan plan) {
            List<DiagnosisBlocker> systemBlockers = plan.blockers().stream()
                    .filter(blocker -> !blocker.userActionable()).toList();
            if (systemBlockers.isEmpty()) {
                return;
            }
            diagnosisCase.block(systemBlockers);
            delegate.emit("diagnosis_blocked", Map.of(
                    "session_id", sessionId,
                    "blockers", systemBlockers));
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
            if (diagnosisCase.status() != DiagnosisStatus.RUNNING) {
                delegate.onToolUseEnd(request, result, durationMs);
                return;
            }
            var evidence = diagnosisCase.recordToolEvidence(request, result);
            delegate.onToolUseEnd(request, result, durationMs);
            Optional.ofNullable(planner).ifPresent(diagnosisPlanner -> applyUpdatedPlan(
                    diagnosisPlanner.updatePlan(
                            diagnosisCase, evidence, conversationContext,
                            operationalContext, capabilities, context)));
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

        private synchronized void finishWaiting(AiMessage finalMessage) {
            emitState();
            delegate.onTurnComplete(finalMessage);
        }

        private synchronized void finishWithBudgetReport(String detail) {
            DiagnosisReport report = new DiagnosisReport(
                    "Diagnosis stopped because the configured budget was exceeded.",
                    List.of(), List.of(), List.of(),
                    List.of(detail), 0.0, true);
            delegate.emit("diagnosis_report", Map.of("session_id", sessionId, "report", report));
            if (diagnosisCase.status() == DiagnosisStatus.RUNNING) {
                diagnosisCase.markDone();
            }
            emitState();
            delegate.onTurnComplete(AiMessage.text(report.summary()));
        }

        private void emitReport(DiagnosisReporter diagnosisReporter) {
            DiagnosisReport report = diagnosisReporter.report(diagnosisCase, context);
            delegate.emit("diagnosis_report", Map.of("session_id", sessionId, "report", report));
            if (!report.missingInformation().isEmpty()) {
                delegate.emit("diagnosis_need_info", Map.of(
                        "session_id", sessionId,
                        "missingInputs", report.missingInformation()));
            }
        }

        private void emitState() {
            stateSnapshot = stateCodec.encode(diagnosisCase);
            delegate.emit("diagnosis_state", Map.of(
                    "session_id", sessionId,
                    "snapshot", stateSnapshot));
        }

        private OrchestrationResult result(AgentRunResult runResult) {
            return new OrchestrationResult(
                    stateSnapshot, runResult, outcome(runResult), diagnosisCase.blockers());
        }

        private DiagnosisOutcome outcome(AgentRunResult runResult) {
            if (runResult.stopReason() == StopReason.BUDGET_EXHAUSTED) {
                return DiagnosisOutcome.BUDGET_LIMITED;
            }
            if (runResult.stopReason() == StopReason.CANCELLED
                    || runResult.stopReason() == StopReason.TIMED_OUT) {
                return DiagnosisOutcome.CANCELLED;
            }
            if (diagnosisCase.status() == DiagnosisStatus.NEED_INFO) {
                return DiagnosisOutcome.WAITING_FOR_USER_INPUT;
            }
            if (diagnosisCase.status() == DiagnosisStatus.BLOCKED) {
                return blockedOutcome(diagnosisCase.blockers());
            }
            if (runResult.stopReason() == StopReason.PROVIDER_ERROR
                    || runResult.stopReason() == StopReason.INTERCEPTOR_ERROR
                    || runResult.stopReason() == StopReason.PERSISTENCE_ERROR
                    || runResult.stopReason() == StopReason.TOOL_PROTOCOL_ERROR) {
                return DiagnosisOutcome.FAILED;
            }
            return diagnosisCase.ledger().all().isEmpty() && diagnosisCase.plan().hypotheses().isEmpty()
                    ? DiagnosisOutcome.NON_INCIDENT_RESPONSE : DiagnosisOutcome.COMPLETED;
        }

        private DiagnosisOutcome blockedOutcome(List<DiagnosisBlocker> blockers) {
            DiagnosisBlockerType type = blockers.getFirst().type();
            return switch (type) {
                case CAPABILITY_UNAVAILABLE -> DiagnosisOutcome.CAPABILITY_UNAVAILABLE;
                case BACKEND_UNHEALTHY -> DiagnosisOutcome.BACKEND_UNHEALTHY;
                case ENVIRONMENT_MISMATCH -> DiagnosisOutcome.ENVIRONMENT_MISMATCH;
                case POLICY_DENIED -> DiagnosisOutcome.POLICY_DENIED;
                case USER_INPUT_REQUIRED -> DiagnosisOutcome.WAITING_FOR_USER_INPUT;
            };
        }
    }
}
