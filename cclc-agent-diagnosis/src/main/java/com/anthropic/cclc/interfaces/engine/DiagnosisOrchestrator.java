package com.anthropic.cclc.interfaces.engine;

import com.anthropic.cclc.application.AgentEventListener;
import com.anthropic.cclc.application.AgentExecutor;
import com.anthropic.cclc.application.InteractivePrompter;
import com.anthropic.cclc.application.PermissionService;
import com.anthropic.cclc.application.diagnosis.DiagnosisPlanner;
import com.anthropic.cclc.domain.agent.AgentBudget;
import com.anthropic.cclc.domain.conversation.CancellationToken;
import com.anthropic.cclc.domain.conversation.Conversation;
import com.anthropic.cclc.domain.diagnosis.DiagnosisCase;
import com.anthropic.cclc.domain.diagnosis.DiagnosisPlan;
import com.anthropic.cclc.domain.message.AiMessage;
import com.anthropic.cclc.domain.permission.PermissionMode;
import com.anthropic.cclc.domain.port.LlmClient;
import com.anthropic.cclc.domain.tool.ExecutionContext;
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
        this.llm = Objects.requireNonNull(llm, "llm");
        this.tools = Objects.requireNonNull(tools, "tools");
        this.budget = Objects.requireNonNull(budget, "budget");
        this.stateCodec = Objects.requireNonNull(stateCodec, "stateCodec");
        this.planner = planner;
        this.systemPrompt = composeSystemPrompt(promptPack);
    }

    public void run(RunRequest request, Conversation conversation,
                    CancellationToken cancel, Consumer<String> onChunk) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(conversation, "conversation");
        Objects.requireNonNull(cancel, "cancel");
        Objects.requireNonNull(onChunk, "onChunk");

        AgentExecutor executor = new AgentExecutor(llm, tools, permissions(), context(request, cancel), budget);
        DiagnosisStateListener listener = listener(request, onChunk);
        planIfConfigured(listener);
        executor.run(conversation, cancel, listener, systemPrompt).join();
    }

    private static String composeSystemPrompt(String promptPack) {
        if (promptPack == null || promptPack.isBlank()) {
            return DIAGNOSIS_SYSTEM_PROMPT;
        }
        return DIAGNOSIS_SYSTEM_PROMPT + "\n\n## Diagnosis PromptPack\n" + promptPack;
    }

    private DiagnosisStateListener listener(RunRequest request, Consumer<String> onChunk) {
        DiagnosisCase diagnosisCase = restoreDiagnosisCase(request);
        return new DiagnosisStateListener(
                new ClaudeStreamJsonListener(request.sessionId(), request.workingDir(), onChunk),
                diagnosisCase,
                stateCodec,
                request.sessionId());
    }

    private void planIfConfigured(DiagnosisStateListener listener) {
        Optional.ofNullable(planner).ifPresent(diagnosisPlanner -> {
            DiagnosisPlan plan = diagnosisPlanner.createPlan(listener.diagnosisCase());
            listener.diagnosisCase().adoptPlan(plan);
            listener.emitPlan(plan);
        });
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

    private static PermissionService permissions() {
        return new PermissionService(
                new ReadOnlyPermissionPolicy(), REJECTING_PROMPTER, PermissionMode.BYPASS);
    }

    private static ExecutionContext context(RunRequest request, CancellationToken cancel) {
        return ExecutionContext.of(Paths.get(request.workingDir()), cancel);
    }

    private static final class DiagnosisStateListener implements AgentEventListener {

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

        private void emitPlan(DiagnosisPlan plan) {
            delegate.emit("diagnosis_plan", Map.of(
                    "session_id", sessionId,
                    "plan", plan));
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
        public void onToolUseEnd(ToolUseRequest request, ToolResult result, long durationMs) {
            diagnosisCase.recordToolEvidence(request, result);
            delegate.onToolUseEnd(request, result, durationMs);
        }

        @Override
        public void onUsage(int inputTokens, int outputTokens, int cacheReadInputTokens) {
            delegate.onUsage(inputTokens, outputTokens, cacheReadInputTokens);
        }

        @Override
        public void onTurnComplete(AiMessage finalMessage) {
            emitState();
            delegate.onTurnComplete(finalMessage);
        }

        @Override
        public void onError(Throwable error) {
            delegate.onError(error);
        }

        private void emitState() {
            delegate.emit("diagnosis_state", Map.of(
                    "session_id", sessionId,
                    "snapshot", stateCodec.encode(diagnosisCase)));
        }
    }
}
