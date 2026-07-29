package com.anthropic.agentkit.domain.tool;

/** Resolves an immutable tool projection for one explicit execution scope. */
@FunctionalInterface
public interface ToolCatalog {

    ToolCatalogSnapshot snapshot(ExecutionContext context);
}
