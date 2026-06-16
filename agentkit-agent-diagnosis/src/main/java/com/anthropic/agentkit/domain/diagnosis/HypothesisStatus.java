package com.anthropic.agentkit.domain.diagnosis;

public enum HypothesisStatus {
    OPEN,
    SUPPORTED,
    CONTRADICTED,
    INSUFFICIENT_EVIDENCE,
    CONFIRMED
}
