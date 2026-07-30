package com.anthropic.agentkit.domain.diagnosis;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * @author alex
 */
class DiagnosisReportTest {

    @Test
    void redactsSecretsAcrossTheEntireReportProjection() {
        DiagnosisReport report = new DiagnosisReport(
                "summary token=report-marker",
                List.of(new RootCauseCandidate(
                        "H-sk-report-marker", "Bearer report-marker",
                        List.of("E-sk-report-marker"), 0.8, true)),
                List.of("E-sk-report-marker"),
                List.of("password=report-marker"),
                List.of("api_key=report-marker"),
                0.8, false);

        assertThat(report.summary()).isEqualTo("***");
        assertThat(report.rootCauseCandidates().getFirst().summary()).isEqualTo("***");
        assertThat(report.toString())
                .doesNotContain("report-marker", "Bearer", "token=", "password=", "api_key=");
    }
}
