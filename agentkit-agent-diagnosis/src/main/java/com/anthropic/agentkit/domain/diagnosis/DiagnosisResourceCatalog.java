package com.anthropic.agentkit.domain.diagnosis;

/**
 * Host-provided port that atomically publishes secret-free diagnosis resources.
 *
 * @author alex
 */
@FunctionalInterface
public interface DiagnosisResourceCatalog {

    DiagnosisResourceCatalogSnapshot snapshot();

    static DiagnosisResourceCatalog empty() {
        return DiagnosisResourceCatalogSnapshot::empty;
    }
}
