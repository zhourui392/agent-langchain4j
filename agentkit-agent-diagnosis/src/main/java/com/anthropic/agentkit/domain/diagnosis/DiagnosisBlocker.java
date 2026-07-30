package com.anthropic.agentkit.domain.diagnosis;

/**
 * Safe, typed explanation of why a diagnosis cannot currently proceed.
 *
 * @author alex
 */
public record DiagnosisBlocker(DiagnosisBlockerType type, String code, String message,
                               String remediation, boolean userActionable) {

    public DiagnosisBlocker {
        if (type == null) {
            throw new NullPointerException("type");
        }
        code = requireText(code, "code");
        message = requireText(message, "message");
        remediation = SecretDataPolicy.sanitize(remediation);
        if (userActionable && type != DiagnosisBlockerType.USER_INPUT_REQUIRED) {
            throw new IllegalArgumentException(
                    "only USER_INPUT_REQUIRED may be userActionable");
        }
    }

    public static DiagnosisBlocker userInput(String code, String message) {
        return new DiagnosisBlocker(
                DiagnosisBlockerType.USER_INPUT_REQUIRED, code, message,
                "Provide the requested diagnosis scope information", true);
    }

    private static String requireText(String value, String name) {
        return SecretDataPolicy.required(value, name);
    }
}
