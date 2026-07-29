package com.anthropic.agentkit.application.interception;

final class InterceptorDecisionReason {

    private InterceptorDecisionReason() {
    }

    static String require(String reason) {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException(
                    "interceptor denial reason must not be blank");
        }
        return reason.trim();
    }
}
