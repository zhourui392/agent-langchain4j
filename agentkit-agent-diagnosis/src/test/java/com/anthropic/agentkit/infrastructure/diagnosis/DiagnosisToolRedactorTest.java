package com.anthropic.agentkit.infrastructure.diagnosis;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * @author alex
 */
class DiagnosisToolRedactorTest {

    @Test
    void redactsCredentialsAcrossBackendResultFormats() {
        String redacted = new DiagnosisToolRedactor().redact("""
                Authorization: Bearer must-not-survive
                api_key=must-not-survive
                token: must-not-survive
                raw sk-mustnotsurvive123456789
                diagnostic line remains
                """);

        assertThat(redacted).doesNotContain("must-not-survive", "mustnotsurvive")
                .contains("Authorization: ***", "api_key=***", "token=***",
                        "diagnostic line remains");
    }
}
