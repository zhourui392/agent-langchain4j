package com.anthropic.cclc.application;

import com.anthropic.cclc.domain.tool.ToolArguments;
import com.anthropic.cclc.domain.tool.ToolInvocation;
import com.anthropic.cclc.domain.tool.ToolUseRequest;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.Map;

final class InvocationFactory {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private InvocationFactory() {
    }

    static ToolInvocation from(ToolUseRequest req) {
        return ToolInvocation.create(req.id(), req.toolName(), parseArguments(req.argumentsJson()));
    }

    private static ToolArguments parseArguments(String json) {
        try {
            return ToolArguments.of(JSON.readValue(json, MAP_TYPE));
        } catch (IOException ex) {
            throw new IllegalArgumentException("invalid tool arguments JSON: " + json, ex);
        }
    }
}
