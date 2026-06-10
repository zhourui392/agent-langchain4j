package com.anthropic.cclc.domain.diagnosis;

import com.anthropic.cclc.domain.tool.ToolResult;
import com.anthropic.cclc.domain.tool.ToolUseRequest;

import java.util.List;
import java.util.Objects;

/**
 * Diagnosis aggregate root: owns plan state and evidence invariants.
 *
 * @author zhourui(V33215020)
 * @since 2026-06-11
 */
public final class DiagnosisCase {

    private final String caseId;
    private final String question;
    private final EvidenceLedger ledger;
    private DiagnosisStatus status;
    private DiagnosisPlan plan;

    private DiagnosisCase(String caseId, String question, DiagnosisStatus status,
                          DiagnosisPlan plan, EvidenceLedger ledger) {
        this.caseId = requireText(caseId, "caseId");
        this.question = requireText(question, "question");
        this.status = Objects.requireNonNull(status, "status");
        this.plan = plan;
        this.ledger = Objects.requireNonNull(ledger, "ledger");
    }

    public static DiagnosisCase open(String caseId, String question) {
        return new DiagnosisCase(caseId, question, DiagnosisStatus.PLANNING, null, new EvidenceLedger());
    }

    public static DiagnosisCase restore(String caseId, String question, DiagnosisStatus status,
                                        DiagnosisPlan plan, List<Evidence> evidence) {
        EvidenceLedger ledger = new EvidenceLedger();
        evidence.forEach(ledger::addExisting);
        return new DiagnosisCase(caseId, question, status, plan, ledger);
    }

    public void adoptPlan(DiagnosisPlan nextPlan) {
        if (status != DiagnosisStatus.PLANNING && status != DiagnosisStatus.RUNNING
                && status != DiagnosisStatus.NEED_INFO) {
            throw new IllegalStateException("cannot adopt plan from " + status);
        }
        this.plan = Objects.requireNonNull(nextPlan, "nextPlan");
        this.status = DiagnosisStatus.RUNNING;
    }

    public Evidence recordToolEvidence(ToolUseRequest request, ToolResult result) {
        requireRunning();
        boolean offPlan = plan == null || !plan.isToolAllowed(request.toolName());
        return ledger.addToolResult(request, result, offPlan);
    }

    public Evidence recordModelInference(String summary) {
        return ledger.addModelInference(summary);
    }

    public boolean canConfirmRootCause(String hypothesisId) {
        requireText(hypothesisId, "hypothesisId");
        return ledger.all().stream().anyMatch(evidence -> evidence.source() != EvidenceSource.MODEL_INFERENCE);
    }

    public void markDone() {
        if (status != DiagnosisStatus.RUNNING && status != DiagnosisStatus.NEED_INFO) {
            throw new IllegalStateException("cannot mark done from " + status);
        }
        status = DiagnosisStatus.DONE;
    }

    public String caseId() {
        return caseId;
    }

    public String question() {
        return question;
    }

    public DiagnosisStatus status() {
        return status;
    }

    public DiagnosisPlan plan() {
        return plan;
    }

    public EvidenceLedger ledger() {
        return ledger;
    }

    private void requireRunning() {
        if (status != DiagnosisStatus.RUNNING) {
            throw new IllegalStateException("diagnosis case is not RUNNING: " + status);
        }
    }

    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value;
    }
}
