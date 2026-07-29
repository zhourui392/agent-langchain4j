package com.anthropic.agentkit.infrastructure.diagnosis;

import com.anthropic.agentkit.application.diagnosis.DiagnosisReporter;
import com.anthropic.agentkit.domain.agent.AgentRunContext;
import com.anthropic.agentkit.domain.diagnosis.DiagnosisCase;
import com.anthropic.agentkit.domain.diagnosis.DiagnosisReport;
import com.anthropic.agentkit.domain.diagnosis.DiagnosisReportValidationResult;
import com.anthropic.agentkit.domain.diagnosis.DiagnosisReportValidator;
import com.anthropic.agentkit.domain.diagnosis.RootCauseCandidate;
import com.anthropic.agentkit.domain.port.LlmClient;
import com.anthropic.agentkit.infrastructure.agent.StructuredAgent;
import com.anthropic.agentkit.infrastructure.agent.TerminalToolSpec;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * LLM reporter that receives reports through the kernel structured-output tool.
 *
 * @author zhourui(V33215020)
 * @since 2026-06-11
 */
public final class StructuredDiagnosisReporter implements DiagnosisReporter {

    private static final Logger log = LoggerFactory.getLogger(StructuredDiagnosisReporter.class);

    private static final String TOOL_NAME = "submit_report";
    private static final String REPORT_SCHEMA = """
            {"type":"object","properties":{\
            "summary":{"type":"string"},\
            "rootCauseCandidates":{"type":"array"},\
            "keyEvidenceIds":{"type":"array"},\
            "recommendedActions":{"type":"array"},\
            "missingInformation":{"type":"array"},\
            "confidence":{"type":"number"},\
            "needHumanCheck":{"type":"boolean"}\
            },"required":["summary","rootCauseCandidates","keyEvidenceIds",\
            "recommendedActions","confidence","needHumanCheck"]}""";
    private static final String SYSTEM_PROMPT =
            "Submit a structured diagnosis report by calling the submit_report tool.";
    private static final TerminalToolSpec REPORT_OUTPUT = new TerminalToolSpec(
            TOOL_NAME, "Submit a structured diagnosis report", REPORT_SCHEMA);

    private final LlmClient llm;
    private final ObjectMapper mapper = new ObjectMapper();
    private final DiagnosisReportValidator validator = new DiagnosisReportValidator();

    public StructuredDiagnosisReporter(LlmClient llm) {
        this.llm = Objects.requireNonNull(llm, "llm");
    }

    @Override
    public DiagnosisReport report(DiagnosisCase diagnosisCase, AgentRunContext context) {
        long startNs = System.nanoTime();
        StructuredAgent agent = new StructuredAgent(llm, SYSTEM_PROMPT, REPORT_OUTPUT, List.of());
        Map<String, Object> payload = agent.run(
                "Create a report for: " + diagnosisCase.question(), context);
        DiagnosisReport report = toReport(payload);
        validate(report, diagnosisCase);
        log.info("diagnosis report created: caseId={}, candidates={}, confidence={}, durationMs={}",
                diagnosisCase.caseId(), report.rootCauseCandidates().size(),
                report.confidence(), elapsedMs(startNs));
        return report;
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
                safeList(dto.missingInformation()),
                dto.confidence(),
                dto.needHumanCheck());
    }

    private void validate(DiagnosisReport report, DiagnosisCase diagnosisCase) {
        DiagnosisReportValidationResult result = validator.validate(report, diagnosisCase.ledger().all());
        if (!result.valid()) {
            log.info("diagnosis report validation failed: errors={}", result.errors().size());
            throw new IllegalStateException("invalid diagnosis report: " + String.join("; ", result.errors()));
        }
        log.info("diagnosis report validation passed: evidenceCount={}", diagnosisCase.ledger().all().size());
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

    private static long elapsedMs(long startNs) {
        return (System.nanoTime() - startNs) / 1_000_000L;
    }

    private record ReportDto(String summary, List<RootCauseCandidateDto> rootCauseCandidates,
                             List<String> keyEvidenceIds, List<String> recommendedActions,
                             List<String> missingInformation, double confidence, boolean needHumanCheck) {
    }

    private record RootCauseCandidateDto(String hypothesisId, String summary, List<String> evidenceIds,
                                         double confidence, boolean confirmed) {
    }
}
