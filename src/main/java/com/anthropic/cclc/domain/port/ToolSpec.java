package com.anthropic.cclc.domain.port;

import java.util.Objects;

public record ToolSpec(String name, String description, String inputSchema) {

    public ToolSpec {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(description, "description");
        Objects.requireNonNull(inputSchema, "inputSchema");
    }
}
