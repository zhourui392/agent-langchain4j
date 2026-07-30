package com.anthropic.agentkit.interfaces.engine;

import com.anthropic.agentkit.domain.diagnosis.ReadinessStatus;

import java.util.List;
import java.util.Objects;

/**
 * Aggregate engine readiness that intentionally contains no connection or credential fields.
 *
 * @author alex
 */
public record DiagnosisReadiness(ReadinessStatus status, DiagnosisMode mode,
                                 List<DiagnosisCapability> capabilities, String reasonCode) {

    public DiagnosisReadiness {
        status = Objects.requireNonNull(status, "status");
        mode = Objects.requireNonNull(mode, "mode");
        capabilities = List.copyOf(Objects.requireNonNull(capabilities, "capabilities"));
        reasonCode = reasonCode == null ? "" : reasonCode.trim();
    }

    public static DiagnosisReadiness unknown() {
        return new DiagnosisReadiness(
                ReadinessStatus.UNAVAILABLE, DiagnosisMode.CONVERSATIONAL,
                List.of(), "READINESS_UNKNOWN");
    }

    public static DiagnosisReadiness conversational() {
        return new DiagnosisReadiness(
                ReadinessStatus.READY, DiagnosisMode.CONVERSATIONAL, List.of(), "");
    }
}
