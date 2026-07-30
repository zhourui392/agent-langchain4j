package com.anthropic.agentkit.domain.diagnosis;

import com.anthropic.agentkit.domain.tool.ToolResult;
import com.anthropic.agentkit.domain.tool.ToolResultStatus;
import com.anthropic.agentkit.domain.tool.ToolUseId;
import com.anthropic.agentkit.domain.tool.ToolUseRequest;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * @author alex
 */
class EvidenceLedgerMetadataTest {

    @Test
    void toolEvidence_shouldPreserveStructuredMetadataAndBoundTextProjection() {
        String largeBody = "first diagnostic line\n" + "x".repeat(20_000)
                + "\nfinal controlled stack";
        ToolResult result = ToolResult.of(ToolResultStatus.SUCCESS, largeBody, Map.of(
                DiagnosisToolMetadata.DATA_SOURCE_ID, "orders-prod-logs",
                DiagnosisToolMetadata.ENVIRONMENT, "prod",
                DiagnosisToolMetadata.MATCHED, "127",
                DiagnosisToolMetadata.RETURNED, "50",
                DiagnosisToolMetadata.TRUNCATED, "true"));

        Evidence evidence = new EvidenceLedger().addToolResult(
                new ToolUseRequest(new ToolUseId("tool-1"), "LogQuery", "{}"),
                result, false);

        assertThat(evidence.summary()).isEqualTo("first diagnostic line");
        assertThat(evidence.rawExcerpt()).hasSizeLessThanOrEqualTo(4096);
        assertThat(evidence.rawExcerpt()).isNotEqualTo(largeBody);
        assertThat(evidence.rawExcerpt())
                .contains("first diagnostic line", "final controlled stack");
        assertThat(evidence.metadata())
                .containsEntry(DiagnosisToolMetadata.DATA_SOURCE_ID, "orders-prod-logs")
                .containsEntry(DiagnosisToolMetadata.ENVIRONMENT, "prod")
                .containsEntry(DiagnosisToolMetadata.MATCHED, "127")
                .containsEntry("offPlan", false)
                .containsEntry("success", true);
        assertThat(evidence.toolUseId()).isEqualTo("tool-1");
    }

    @Test
    void toolEvidence_shouldSeparateBackendObservationFromLedgerRecordingTime() {
        Instant recordedAt = Instant.parse("2026-07-30T02:00:00Z");
        EvidenceLedger ledger = new EvidenceLedger(
                Clock.fixed(recordedAt, ZoneOffset.UTC));
        ToolResult result = ToolResult.of(
                com.anthropic.agentkit.domain.tool.ToolResultStatus.SUCCESS, "error",
                Map.of(DiagnosisToolMetadata.QUERY_END, "2026-07-30T01:00:00Z"));

        Evidence evidence = ledger.addToolResult(
                new ToolUseRequest(new ToolUseId("tool-time"), "LogQuery", "{}"),
                result, false);

        assertThat(evidence.observedAt()).isEqualTo(
                Instant.parse("2026-07-30T01:00:00Z"));
        assertThat(evidence.recordedAt()).isEqualTo(recordedAt);
    }

    @Test
    void toolEvidence_shouldRemoveSecretBearingMetadataKeysAndValues() {
        ToolResult result = ToolResult.of(ToolResultStatus.SUCCESS, "safe result", Map.of(
                DiagnosisToolMetadata.DATA_SOURCE_ID, "orders-prod-logs",
                "Authorization", "Bearer must-not-survive",
                "diagnosis.queryId", "api_key=must-not-survive",
                "diagnosis.backendMessage", "credential: must-not-survive"));

        Evidence evidence = new EvidenceLedger().addToolResult(
                new ToolUseRequest(new ToolUseId("tool-secret-metadata"), "LogQuery", "{}"),
                result, false);

        assertThat(evidence.metadata()).doesNotContainKey("Authorization");
        assertThat(evidence.metadata().toString())
                .doesNotContain("must-not-survive", "Bearer", "api_key=", "credential:",
                        "password");
        assertThat(evidence.metadata())
                .containsEntry(DiagnosisToolMetadata.DATA_SOURCE_ID, "orders-prod-logs");

        Evidence nestedEvidence = new Evidence(
                "evidence-nested", EvidenceSource.TOOL_RESULT, "safe", "safe",
                "LogQuery", "tool-nested", Map.of(
                        "diagnosis.nested", Map.of(
                                "description", List.of(
                                        "safe", "token=nested-must-not-survive"),
                                "password", "nested-must-not-survive")),
                Instant.EPOCH);
        assertThat(nestedEvidence.metadata().toString())
                .doesNotContain("nested-must-not-survive", "token=", "password")
                .contains("safe");
    }

    @Test
    void toolEvidence_shouldRedactSecretBearingSummaryAndRawExcerpt() {
        ToolResult result = ToolResult.of(ToolResultStatus.SUCCESS,
                "Authorization: Bearer must-not-survive\napi_key=must-not-survive-either",
                Map.of());

        Evidence evidence = new EvidenceLedger().addToolResult(
                new ToolUseRequest(new ToolUseId("tool-secret-body"), "LogQuery", "{}"),
                result, false);

        assertThat(evidence.summary()).isEqualTo("***");
        assertThat(evidence.rawExcerpt()).isEqualTo("***");
        assertThat(evidence.toString())
                .doesNotContain("must-not-survive", "Bearer", "api_key=");
    }

    @Test
    void toolEvidence_shouldPreserveUsefulContentThatWasAlreadyRedacted() {
        ToolResult result = ToolResult.of(ToolResultStatus.SUCCESS,
                "ERROR useful diagnostic line apiKey=*** Authorization: Bearer ***",
                Map.of());

        Evidence evidence = new EvidenceLedger().addToolResult(
                new ToolUseRequest(new ToolUseId("tool-safe-placeholder"), "LogQuery", "{}"),
                result, false);

        assertThat(evidence.summary()).contains("useful diagnostic line", "apiKey=***");
        assertThat(evidence.rawExcerpt()).contains(
                "useful diagnostic line", "apiKey=***", "Bearer ***");
    }
}
