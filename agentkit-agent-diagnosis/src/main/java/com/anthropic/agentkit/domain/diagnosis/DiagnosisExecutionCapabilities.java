package com.anthropic.agentkit.domain.diagnosis;

import java.util.Objects;
import java.util.Set;

/**
 * Immutable tool generation shared by diagnosis planning and execution for one run.
 *
 * @author alex
 */
public record DiagnosisExecutionCapabilities(long generation, Set<String> toolNames,
                                              DiagnosisResourceCatalogSnapshot resources) {

    public DiagnosisExecutionCapabilities {
        toolNames = SecretDataPolicy.sanitizeSet(
                Objects.requireNonNull(toolNames, "toolNames"), "toolName");
        resources = resources == null ? DiagnosisResourceCatalogSnapshot.empty() : resources;
    }

    public DiagnosisExecutionCapabilities(long generation, Set<String> toolNames) {
        this(generation, toolNames, DiagnosisResourceCatalogSnapshot.empty());
    }
}
