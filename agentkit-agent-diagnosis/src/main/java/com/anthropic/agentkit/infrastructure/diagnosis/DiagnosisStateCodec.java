package com.anthropic.agentkit.infrastructure.diagnosis;

import com.anthropic.agentkit.domain.diagnosis.DiagnosisCase;
import com.anthropic.agentkit.domain.diagnosis.DiagnosisPlan;
import com.anthropic.agentkit.domain.diagnosis.DiagnosisStatus;
import com.anthropic.agentkit.domain.diagnosis.Evidence;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

/**
 * Serializes diagnosis state snapshots exchanged with the host.
 *
 * @author zhourui(V33215020)
 * @since 2026-06-11
 */
public final class DiagnosisStateCodec {

    private static final int SCHEMA_VERSION = 1;

    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());

    public String encode(DiagnosisCase diagnosisCase) {
        Snapshot snapshot = new Snapshot(
                SCHEMA_VERSION,
                diagnosisCase.caseId(),
                diagnosisCase.question(),
                diagnosisCase.status(),
                diagnosisCase.plan(),
                diagnosisCase.ledger().all());
        try {
            return mapper.writeValueAsString(snapshot);
        } catch (IOException ex) {
            throw new IllegalStateException("failed to encode diagnosis state", ex);
        }
    }

    public Optional<DiagnosisCase> decode(String json) {
        if (json == null || json.isBlank()) {
            return Optional.empty();
        }
        try {
            Snapshot snapshot = mapper.readValue(json, Snapshot.class);
            if (snapshot.schemaVersion() != SCHEMA_VERSION) {
                return Optional.empty();
            }
            return Optional.of(DiagnosisCase.restore(
                    snapshot.caseId(),
                    snapshot.question(),
                    snapshot.status(),
                    snapshot.plan(),
                    snapshot.evidence()));
        } catch (IOException | RuntimeException ex) {
            return Optional.empty();
        }
    }

    private record Snapshot(int schemaVersion, String caseId, String question, DiagnosisStatus status,
                            DiagnosisPlan plan, List<Evidence> evidence) {
    }
}
