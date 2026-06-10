package com.anthropic.cclc.infrastructure.diagnosis;

import com.anthropic.cclc.application.AgentEventListener;
import com.anthropic.cclc.application.AgentExecutor;
import com.anthropic.cclc.application.diagnosis.DiagnosisReporter;
import com.anthropic.cclc.domain.conversation.CancellationToken;
import com.anthropic.cclc.domain.conversation.Conversation;
import com.anthropic.cclc.domain.conversation.SessionId;
import com.anthropic.cclc.domain.diagnosis.DiagnosisCase;
import com.anthropic.cclc.domain.diagnosis.DiagnosisReport;
import com.anthropic.cclc.domain.diagnosis.DiagnosisReportValidationResult;
import com.anthropic.cclc.domain.diagnosis.DiagnosisReportValidator;
import com.anthropic.cclc.domain.diagnosis.RootCauseCandidate;
import com.anthropic.cclc.domain.message.UserMessage;
import com.anthropic.cclc.domain.port.LlmClient;
import com.anthropic.cclc.domain.tool.ToolRegistry;
import com.anthropic.cclc.infrastructure.tools.StructuredOutputTool;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * LLM reporter that receives reports through the kernel structured-output tool.
 *
 * @author zhourui(V33215020)
 * @since 2026-06-11
 */
public final class StructuredDiagnosisReporter implements DiagnosisReporter {

    private static final String TOOL_NAME = "submit_report";
    private static final String REPORT_SCHEMA = """
            {"type":"object","properties":{\
            "summary":{"type":"string"},\
            "rootCauseCandidates":{"type":"array"},\
            "keyEvidenceIds":{"type":"array"},\
            "recommendedActions":{"type":"array"},\
            "confidence":{"type":"number"},\
            "needHumanCheck":{"type":"boolean"}\
            },"required":["summary","rootCauseCandidates","keyEvidenceIds",\
            "recommendedActions","confidence","needHumanCheck"]}""";
    private static final String SYSTEM_PROMPT =
            "Submit a structured diagnosis report by calling the submit_report tool.";

    private final LlmClient llm;
    private final ObjectMapper mapper = new ObjectMapper();
    private final DiagnosisReportValidator validator = new DiagnosisReportValidator();

    public StructuredDiagnosisReporter(LlmClient llm) {
        this.llm = Objects.requireNonNull(llm, "llm");
    }

    @Override
    public DiagnosisReport report(DiagnosisCase diagnosisCase) {
        AtomicReference<Map<String, Object>> acceptedReport = new AtomicReference<>();
        ToolRegistry tools = new ToolRegistry().register(new StructuredOutputTool(
                TOOL_NAME, "Submit a structured diagnosis report", REPORT_SCHEMA, acceptedReport::set));
        Conversation conversation = conversationFor(diagnosisCase);

        new AgentExecutor(llm, tools).run(conversation, new CancellationToken(),
                AgentEventListener.NO_OP, SYSTEM_PROMPT).join();
        DiagnosisReport report = toReport(acceptedReport.get());
        validate(report, diagnosisCase);
        return report;
    }

    private static Conversation conversationFor(DiagnosisCase diagnosisCase) {
        Conversation conversation = new Conversation(SessionId.fresh());
        conversation.append(UserMessage.of("Create a report for: " + diagnosisCase.question()));
        return conversation;
    }

    private DiagnosisReport toReport(Map<String, Object> payload) {
        if (payload == null) {
            throw new IllegalStateException("reporter did not call " + TOOL_NAME);
        }
        ReportDto dto = mapper.convertValue(payload, ReportDto.class);
        return new DiagnosisReport(
                dto.summary(),
                toCandidates(dto.rootCauseCandidates()),
                safeList(dto.keyEvidenceIds()),
                safeList(dto.recommendedActions()),
                dto.confidence(),
                dto.needHumanCheck());
    }

    private void validate(DiagnosisReport report, DiagnosisCase diagnosisCase) {
        DiagnosisReportValidationResult result = validator.validate(report, diagnosisCase.ledger().all());
        if (!result.valid()) {
            throw new IllegalStateException("invalid diagnosis report: " + String.join("; ", result.errors()));
        }
    }

    private static List<RootCauseCandidate> toCandidates(List<RootCauseCandidateDto> items) {
        return safeList(items).stream()
                .map(item -> new RootCauseCandidate(
                        item.hypothesisId(),
                        item.summary(),
                        safeList(item.evidenceIds()),
                        item.confidence(),
                        item.confirmed()))
                .toList();
    }

    private static <T> List<T> safeList(List<T> items) {
        return items == null ? List.of() : items;
    }

    private record ReportDto(String summary, List<RootCauseCandidateDto> rootCauseCandidates,
                             List<String> keyEvidenceIds, List<String> recommendedActions,
                             double confidence, boolean needHumanCheck) {
    }

    private record RootCauseCandidateDto(String hypothesisId, String summary, List<String> evidenceIds,
                                         double confidence, boolean confirmed) {
    }
}
