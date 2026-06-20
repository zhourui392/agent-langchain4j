package com.anthropic.agentkit.domain.coding;

import java.util.Objects;

/**
 * One file mutation produced by the coder role.
 *
 * @param path       repository-relative path of the file
 * @param changeType kind of mutation
 * @param diff       unified-diff excerpt or free-form description; may be blank for DELETE
 */
public record FileChange(String path, FileChangeType changeType, String diff) {

    public FileChange {
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("path must not be blank");
        }
        changeType = Objects.requireNonNull(changeType, "changeType");
        diff = diff == null ? "" : diff;
    }
}
