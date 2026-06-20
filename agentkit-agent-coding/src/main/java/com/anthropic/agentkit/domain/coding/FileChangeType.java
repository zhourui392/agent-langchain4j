package com.anthropic.agentkit.domain.coding;

/**
 * Kind of file mutation recorded in a {@link FileChange}.
 */
public enum FileChangeType {
    CREATE,
    EDIT,
    DELETE
}
