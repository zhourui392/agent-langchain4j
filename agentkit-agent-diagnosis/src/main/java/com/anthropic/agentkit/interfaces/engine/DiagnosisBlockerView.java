package com.anthropic.agentkit.interfaces.engine;

import com.anthropic.agentkit.domain.diagnosis.DiagnosisBlocker;
import com.anthropic.agentkit.domain.diagnosis.DiagnosisBlockerType;

/**
 * Secret-free public projection of a diagnosis blocker.
 *
 * @author alex
 */
public record DiagnosisBlockerView(DiagnosisBlockerType type, String code, String message,
                                   String remediation, boolean userActionable) {

    static DiagnosisBlockerView from(DiagnosisBlocker blocker) {
        return new DiagnosisBlockerView(
                blocker.type(), blocker.code(), blocker.message(), blocker.remediation(),
                blocker.userActionable());
    }
}
